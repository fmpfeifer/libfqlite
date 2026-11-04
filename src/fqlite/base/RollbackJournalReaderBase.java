package fqlite.base;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import fqlite.descriptor.AbstractDescriptor;
import fqlite.descriptor.TableDescriptor;
import fqlite.pattern.SerialTypeMatcher;
import fqlite.types.CarverTypes;
import fqlite.util.Auxiliary;
import fqlite.util.FreeblockReconstructor;
import fqlite.util.RandomAccessFileReader;

/**
 * The class analyses a Rollback Journal file and writes the found records into a file.
 * 
 * From the SLite documentation:
 * 
 * "The rollback journal is usually created when a transaction is first started and 
 *  is usually deleted when a transaction commits or rolls back. The rollback journal file 
 *  is essential for implementing the atomic commit and rollback capabilities of SQLite."
 * 
 * 
 * 
 * @author pawlaszc
 *
 */
public abstract class RollbackJournalReaderBase extends Base {

	public static final String MAGIC_HEADER_STRING = "d9d505f920a163d7";
	
	/* An asynchronous channel for reading, writing, and manipulating a file. */
	public RandomAccessFileReader file;

	/* pagesize */
	int ps;

	/* path to RollbackJournal-file */
	String path;

	/* flag for already visited Bytes of the page */
	BitSet visit = null;

	
	/* reference to the MAIN class */
	Job job;

	/* number of page that is currently analyzed */
	int pagenumber_rol;
	int pagenumber_maindb;
	
	long pagecount;
	long nounce;
	long pages;
	long sectorsize;
	long journalpagesize;

	boolean withoutROWID = false;


	/* offers a lot of useful utility functions */
	private Auxiliary ct;

	/* knowlegde store */
	private StringBuffer firstcol = new StringBuffer();

	/* buffer that holds the current page */
	protected ByteBuffer buffer;

	public List<TableDescriptor> tables = new LinkedList<TableDescriptor>();
	/* this is a multi-threaded program -> all data are saved to the list first */

	/* outputlist */
	protected Queue<SqliteInternalRow> output = new ConcurrentLinkedQueue<SqliteInternalRow>();
	
	/* file pointer */
	int journalpointer = 0;
	

	/**
	 * Constructor.
	 * 
	 * @param path    full qualified file name to the RollbackJournal archive
	 * @param job reference to the Job class
	 */
	public RollbackJournalReaderBase(String path, Job job) {
		this.path = path;
		this.job = job;
		this.ct = new Auxiliary(job);
	}

	/**
	 * This method is the main processing loop. First the header is analyzed.
	 * Afterwards all write ahead frames are recovered.
	 * 
	 * @throws IOException if an I/O error occurs
	 */
	public void parse() throws IOException {
		Path p = Paths.get(path);

		/* try to open the journal file in read-only mode */
		try {
			file = new RandomAccessFileReader(p);
		} catch (Exception e) {
            err("Cannot open RollbackJournal-file", p.getFileName());
			return;
		}

		try {
			parseJournal();
		} finally {
			file.close();
		}
	}

	private static boolean isPowerOfTwo(long v, long min, long max) {
		return v >= min && v <= max && (v & (v - 1)) == 0;
	}

	private boolean isJournalHeader(long position) throws IOException {
		if (position + 8 > file.size())
			return false;
		ByteBuffer b = file.allocateAndReadBuffer(position, 8);
		return b != null && Auxiliary.bytesToHex(b.array()).equals(MAGIC_HEADER_STRING);
	}

	/**
	 * A rollback journal consists of one or more segments. Each segment starts with a
	 * header (padded to the sector size) followed by page records:
	 *
	 * 4 bytes page number | page content | 4 bytes checksum
	 *
	 * Journal header format:
	 *
	 * 0	8	Header string:  0xd9, 0xd5, 0x05, 0xf9, 0x20, 0xa1, 0x63, 0xd7
	 * 8	4	The "Page Count" - The number of pages in the next segment of the journal, or -1 to mean all content to the end of the file
	 * 12	4	A random nonce for the checksum
	 * 16	4	Initial size of the database in pages
	 * 20	4	Size of a disk sector assumed by the process that wrote this journal.
	 * 24	4	Size of pages in this journal.
	 *
	 * Source: https://www.sqlite.org/fileformat.html#the_rollback_journal
	 */
	private void parseJournal() throws IOException {

		if(file.size() <= 512)
		{
			info("RollbackJournal-File is empty. Skip analyzing.");
			return;
		}

		if (ps <= 0)
			ps = job.ps;

		long segment = 0;
		long sector = 512;
		boolean firstSegment = true;
		int numberofpages = 0;

		while (segment + 28 <= file.size()) {

			ByteBuffer header = file.allocateAndReadBuffer(segment, 28);
			if (header == null)
				break;

			byte head[] = new byte[8];
			header.get(head);
			boolean valid = Auxiliary.bytesToHex(head).equals(MAGIC_HEADER_STRING);

			if (valid) {
				info("header is okay. seems to be an rollback journal file.");
			} else if (firstSegment) {
				/* the header may have been zeroed (journal_mode=PERSIST) - the page records can still be there */
				info("sorry. doesn't seem to be an rollback journal file. Wrong header.");
				warning("Doesn't seem to be an valid rollback journal file. Wrong header. Trying default values.");
			} else {
				break;
			}

			pagecount = Integer.toUnsignedLong(header.getInt());
			info(" pagecount ", pagecount);
			nounce = Integer.toUnsignedLong(header.getInt());
			info(" nounce ", nounce);
			pages = Integer.toUnsignedLong(header.getInt());
			info(" pages ", pages);
			sectorsize = Integer.toUnsignedLong(header.getInt());
			info(" sector size  ", sectorsize);
			journalpagesize = Integer.toUnsignedLong(header.getInt());
			info(" journal page size  ", journalpagesize);

			/* number of page records in this segment, -1 means: up to the end of the file */
			long count = -1;
			if (valid) {
				if (isPowerOfTwo(sectorsize, 32, 65536))
					sector = sectorsize;
				if (isPowerOfTwo(journalpagesize, 512, 65536))
					ps = (int) journalpagesize;
				if (pagecount != 0xFFFFFFFFL && pagecount != 0)
					count = pagecount;
			}
			firstSegment = false;

			/* the first page record follows the header, which is padded to the sector size */
			long pointer = segment + sector;
			long nextSegment = -1;
			long n = 0;

			while ((count < 0 || n < count) && pointer + 4 + ps <= file.size()) {

				if (count < 0 && n > 0) {
					/* is there a new segment? Headers are aligned to the sector size */
					long aligned = ((pointer + sector - 1) / sector) * sector;
					if (isJournalHeader(aligned)) {
						nextSegment = aligned;
						break;
					}
				}

				journalpointer = (int) pointer;

				/* get the page number of the journal page in main db */
				ByteBuffer pn = file.allocateAndReadBuffer(pointer, 4);
				if (pn == null)
					break;
				pagenumber_maindb = pn.getInt();
				debug("pagenumber of journal-entry ", pagenumber_maindb);

				/* now we can read the page - it follows immediately after the page number */
				buffer = readPage();
				if (buffer == null)
					break;

				numberofpages++;
				pagenumber_rol = numberofpages;

				try {
					analyzePage();
				} catch (RuntimeException e) {
					/* corrupted page - go on with the next one */
					err("Error while analyzing journal page ", pagenumber_rol, ": ", e);
				}

				/* currentpos + 4 Byte for the page number in mainDB + pagesize + 4 Byte for Checksum */
				pointer += 4 + ps + 4;
				n++;
			}

			if (nextSegment < 0) {
				if (count < 0)
					break;
				/* the next segment header starts at the next sector boundary */
				nextSegment = ((pointer + sector - 1) / sector) * sector;
			}
			if (nextSegment <= segment)
				break;
			segment = nextSegment;
		}

		info("Lines after RollbackJournal-file recovery: ", output.size());
		info("Number of pages in RollbackJournal-file", numberofpages);
	}

	/**
	 * Analyze the actual database page and try to recover regular and deleted content.
	 *
	 * @return int success
	 */
	private boolean isWithoutRowidTablePage(int pagenumber) {
		if (pagenumber > 0 && pagenumber < job.pages.length) {
			AbstractDescriptor ad = job.pages[pagenumber];
			return ad instanceof TableDescriptor && !ad.ROWID;
		}
		return false;
	}

	public int analyzePage() {

		withoutROWID = false;

		/* every journal record holds a different page -> start with a clean state */
		visit = new BitSet(ps);
		firstcol.setLength(0);

		buffer.position(0);
		byte pageType = buffer.get();
		buffer.position(0);

		/* check type of the page by reading the first byte */
		int type = Auxiliary.getPageType(pageType);

		/* mark bytes as visited */
		visit.set(0, 2);

		if (type == 0) {
			/*
			 * if page was dropped - because of a DROP TABLE command - first 8 Bytes are
			 * zero-bytes, otherwise it seems to be an overflow page
			 */
			buffer.position(0);
			int checksum = buffer.getInt();
			if (checksum == 0) {
				info(" DROPPED PAGE !!!");
				/* no overflow page -> carve for data records - we do our best! ;-) */
				carve(null);
			}
			return 0;
		}

		if (type < 0) {
			info("No Data page. ", pagenumber_rol);
			return -1;
		} else if (type == 12) {
			info("Internal Table page ", pagenumber_rol);
			return -1;
		} else if (type == 10) {
			info("Index leaf page ", pagenumber_rol);
			// note: WITHOUT ROWID tables are saved here.
			withoutROWID = true;
		} else if (type == 2) {
			/* interior pages of index b-trees: only the ones of WITHOUT ROWID tables hold table records */
			if (!isWithoutRowidTablePage(pagenumber_maindb)) {
				info("Index interior page ", pagenumber_rol);
				return -1;
			}
			withoutROWID = true;
		} else {
			info("Data page ", pagenumber_rol, " Offset: ", (journalpointer + 4));
		}

		/* number of cell pointers at offset 3-4 of this page */
		byte cpn[] = new byte[2];
		buffer.position(3);
		buffer.get(cpn);

		/* start of the cell content region */
		byte ccr[] = new byte[2];
		buffer.position(5);
		buffer.get(ccr);
		int ccrstart = Auxiliary.TwoByteBuffertoInt(ccr);
		/* a value of zero means 65536 */
		if (ccrstart == 0)
			ccrstart = 65536;

		/* mark as visited */
		visit.set(2, 8);

		int cp = Auxiliary.TwoByteBuffertoInt(cpn);
		/* the cell pointer array can not be larger than the page (corrupted page) */
		/* interior pages have a 12 byte header (with the right most child pointer) */
		int pageheader = (type == 2) ? 12 : 8;
		cp = Math.min(cp, (ps - pageheader) / 2);
		debug(" number of cells: ", cp, " type of page ", type);
		job.numberofcells.addAndGet(cp);

		int headerend = pageheader + (cp * 2);
		visit.set(0, headerend);

		/***************************************************************
		 * STEP 2: Cell pointer array scan
		 ***************************************************************/
		int last = 0;
		java.util.Map<Integer, java.util.Map<Integer, Integer>> textLengths = new java.util.HashMap<>();
		for (int i = 0; i < cp; i++) {

			byte pointer[] = new byte[2];
			buffer.position(pageheader + 2 * i);
			buffer.get(pointer);
			int celloff = Auxiliary.TwoByteBuffertoInt(pointer);

			if (last > 0 && celloff == last) {
				continue;
			}
			last = celloff;

			SqliteInternalRow row = null;
			/* cells of interior index pages start with the 4 byte page number of the left child */
			int recordstart = (type == 2) ? celloff + 4 : celloff;
			if (celloff > 0 && recordstart < buffer.limit()) {
				if (type == 2)
					visit.set(celloff, recordstart);
				try {
					row = ct.readRecord(recordstart, buffer, pagenumber_maindb, visit, type, Integer.MAX_VALUE, firstcol, withoutROWID, journalpointer + 4);
				} catch (IOException | RuntimeException e) {
					debug("Could not read cell at offset ", celloff, " of journal page ", pagenumber_rol, ": ", e);
				}
			}

			if (null != row) {
				row.setSource(RecordSource.ROLLBACK_JOURNAL);

				/* R*Tree virtual table: decode the entries of the shadow node table */
				for (SqliteInternalRow vrow : RTreeDecoder.decode(job, row)) {
					output.add(vrow);
					job.addRow(vrow);
				}

				output.add(row);
				/* make the record available through the Job API too */
				FreeblockReconstructor.addTextLengths(textLengths, row);
				job.addRow(row);
			}
		}

		debug("finished STEP2 -> cellpoint array completed");

		/* deleted records in the freeblocks: reconstructed with the bounds of the freeblocks and the schema */
		if ((type == 8 || type == 10) && pagenumber_maindb > 0 && pagenumber_maindb < job.pages.length
				&& job.pages[pagenumber_maindb] instanceof TableDescriptor && (type == 8) == job.pages[pagenumber_maindb].ROWID) {
			try {
				FreeblockReconstructor.recoverFreeblocks(ct, buffer, (TableDescriptor) job.pages[pagenumber_maindb], type == 10,
						textLengths, visit, journalpointer + 4L, RecordSource.ROLLBACK_JOURNAL, job::addRow);
			} catch (RuntimeException e) {
				debug("Could not reconstruct the freeblocks of page ", pagenumber_rol, ": ", e);
			}
		}

		/***************************************************************
		 * STEP 3: Scan unallocated space between header and the cell content region
		 ***************************************************************/
		try {
			buffer.position(Math.min(headerend, buffer.limit()));

			/* skip pointers of deleted records behind the official cell pointer array */
			/* the unallocated area ends where the cell content area starts - never read into the cells */
			int unallocatedEnd = Math.min(ccrstart, ps);

			byte garbage[] = new byte[2];
			int garbageoffset;
			do {
				if (buffer.remaining() < 2 || buffer.position() + 2 > unallocatedEnd)
					break;
				buffer.get(garbage);
				garbageoffset = Auxiliary.TwoByteBuffertoInt(garbage);
			} while (buffer.position() < unallocatedEnd && garbageoffset > 0);

			/* skip all zeros - no information to recover just empty space */
			byte zerob = 0;
			while (buffer.position() < unallocatedEnd && zerob == 0) {
				zerob = buffer.get();
			}
			visit.set(headerend, buffer.position());
			if (buffer.position() > 0)
				buffer.position(buffer.position() - 1);

			if (ccrstart - buffer.position() > 3) {
				SqliteInternalRow row = ct.readRecord(buffer.position(), buffer, pagenumber_maindb, visit, type,
						ccrstart - buffer.position(), firstcol, withoutROWID, journalpointer + 4);
				if (null != row) {
					row.setRecordType(Global.DELETED_RECORD_IN_PAGE + row.getRecordType());
					row.setSource(RecordSource.ROLLBACK_JOURNAL);
					output.add(row);
					job.addRow(row);
				}
			}

			/***************************************************************
			 * STEP 4: carve the rest of the page
			 ***************************************************************/
			carve(null);

		} catch (IOException | RuntimeException err) {
			err("Error while carving journal page ", pagenumber_rol, ": ", err);
			return -1;
		}

		return 0;
	}

	/**
	 * Quick lookup. Does a given hex-String starts with Zeros?
	 * 
	 * @param s the String to check
	 * @return true, if zero bytes could be found
	 */
	static boolean allCharactersZero(String s) {
		if (!s.startsWith("0000"))
			return false;

		int n = s.length();
		for (int i = 1; i < n; i++)
			if (s.charAt(i) != s.charAt(0))
				return false;

		return true;
	}


	/**
	 * Starting with the current position of the RollbackJournal-ByteBuffer 
	 * 
	 * read the next db-page.
	 * 
	 * @return the buffer with the page
	 * @throws IOException if an error occurs
	 */
	protected ByteBuffer readPage() throws IOException {
		/* the page follows the 4 byte page number of the journal record */
		return file.allocateAndReadBuffer(journalpointer + 4L, ps);
	}

	/**
	 * This method is called to carve a data page for records.
	 * 
	 * @param buffer the buffer
	 * @param crv Carver object
	 */
	public void carve(ByteBuffer buffer, Carver crv) {
		if (null != buffer)
			this.buffer = buffer;
		carve(crv);
	}

	
	/**
	 *  This method can be used to write the result to a file or
	 *  to update tables in the user interface (in gui-mode). 
	 */
	public abstract void output();

	/**
	 * Check the BitSet for gaps, i.e. regions we still have to carve.
	 * 
	 * @return the gaps found
	 */
	public LinkedList<Gap> findGaps() {
		LinkedList<Gap> gaps = new LinkedList<Gap>();

		int from = 0;

		/* are there any regions left in the page ? */
		for (int i = 0; i < ps; i++) {

			if (!visit.get(i)) {
				from = i;

				int to = i;

				while (!visit.get(++i) && i < (ps - 1)) {
					to++;
				}

				if (to - from > 10) {

					/* check for zero bytes */
					/* only zeros behind a (possible) 4 byte freeblock header: nothing to recover */
					boolean isNull = Auxiliary.isEmptyArea(buffer, from, to);
					// skip NULL-Byte areas - mark as visited
					if (isNull)
						visit.set(from, to);
					else {

						gaps.add(new Gap(from, to));
					}
				}
				from = i;

			}

		} // end of finding gaps in BitSet

		return gaps;
	}

	/**
	 * This method is called to carve a data page for records.
	 * 
	 * @param crv the Carver object
	 */
	public void carve(Carver crv) {

		Carver c = crv;

		if (null == c)
			/* no type could be found in the first byte */
			/* Maybe the whole page was drop because of a drop component command ? */
			/* start carving on the complete page */
		{
			c = new Carver(job, buffer, visit, pagenumber_maindb);
			c.source = RecordSource.ROLLBACK_JOURNAL;
			c.pageFileOffset = journalpointer + 4L;
		}

		// Matcher mat = null;
		// boolean match = false;

		/* try to get component schema for the current page, if possible */
		TableDescriptor tdesc = null;
		if (job.pages.length > pagenumber_maindb) {
			AbstractDescriptor ad = job.pages[pagenumber_maindb];
			if (ad instanceof TableDescriptor)
				tdesc = (TableDescriptor) ad;
		}

		List<TableDescriptor> tab = tables;
		debug(" tables :: ", tables.size());

		if (null != tdesc) {
			/* there is a schema for this page */
			tab = new LinkedList<TableDescriptor>();
			tab.add(tdesc);
			debug(" added tdsec ");
		} else {
			warning(" No component description!");
			tab = tables;
		}

		LinkedList<Gap> gaps = findGaps();
		if (gaps.size() == 0) {
			debug("no gaps anymore. Stopp search");
			return;
		}

		/* try out all component schema(s) */
		for (int n = 0; n < tab.size(); n++) {
			tdesc = tab.get(n);
			debug("pagenumber :: ", pagenumber_maindb, " component size :: ", tab.size());
			debug("n ", n);
			// TableDescriptor tdb = tab.get(n);

			/* access pattern for a particular component */
			String tablename = tab.get(n).tblname;
			debug("Check component : ", tablename);
			if (tablename.startsWith("__UNASSIGNED"))
				continue;
			/* create matcher object for constrain check */
			SerialTypeMatcher stm = new SerialTypeMatcher(buffer);

			gaps = findGaps();

			for (int a = 0; a < gaps.size(); a++) {

				Gap next = gaps.get(a);

				if (next.to - next.from > 10)
					/* do we have at least one match ? */
					if (c.carve(next.from + 4, next.to, stm, CarverTypes.NORMAL, tab.get(n), firstcol) != Global.CARVING_ERROR) {
						debug("*****************************  STEP NORMAL finished with matches");

					}
			}

			gaps = findGaps();

			for (int a = 0; a < gaps.size(); a++) {

				Gap next = gaps.get(a);

				if (c.carve(next.from + 4, next.to, stm, CarverTypes.COLUMNSONLY, tab.get(n), firstcol) != Global.CARVING_ERROR) {
					debug("*****************************  STEP COLUMNSONLY finished with matches");

				}
			}

			gaps = findGaps();

			for (int a = 0; a < gaps.size(); a++) {

				Gap next = gaps.get(a);

				if (c.carve(next.from + 4, next.to, stm, CarverTypes.FIRSTCOLUMNMISSING, tab.get(n), firstcol) != Global.CARVING_ERROR) {
					debug("*****************************  STEP FIRSTCOLUMNMISSING finished with matches");

				}

			}

		}
	}
}
