package fqlite.base;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import fqlite.descriptor.AbstractDescriptor;
import fqlite.descriptor.IndexDescriptor;
import fqlite.descriptor.TableDescriptor;
import fqlite.parser.SQLiteSchemaParser;
import fqlite.util.Auxiliary;
import fqlite.util.ByteSeqSearcher;
import fqlite.util.Logger;
import fqlite.util.LongPositionByteBuffer;
import fqlite.util.LongPositionByteBufferWrapper;
import fqlite.util.RandomAccessFileReader;


/*
---------------
Job.java
---------------
(C) Copyright 2020.

Original Author:  Dirk Pawlaszczyk
Contributor(s):   -;


Project Info:  http://www.hs-mittweida.de

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Dieses Programm ist Freie Software: Sie können es unter den Bedingungen
der GNU General Public License, wie von der Free Software Foundation,
Version 3 der Lizenz oder (nach Ihrer Wahl) jeder neueren
veröffentlichten Version, weiterverbreiten und/oder modifizieren.

Dieses Programm wird in der Hoffnung, dass es nützlich sein wird, aber
OHNE JEDE GEWÄHRLEISTUNG, bereitgestellt; sogar ohne die implizite
Gewährleistung der MARKTFÄHIGKEIT oder EIGNUNG FÜR EINEN BESTIMMTEN ZWECK.
Siehe die GNU General Public License für weitere Details.

Sie sollten eine Kopie der GNU General Public License zusammen mit diesem
Programm erhalten haben. Wenn nicht, siehe <http://www.gnu.org/licenses/>.

*/
/**
 * Core application class. It is used to recover lost SQLite records from a
 * sqlite database file. As a carving utility, it is binary based and can recover
 * deleted entries from sqlite 3.x database files.
 * 
 * Note: For each database file exactly one Job object is created. This class has
 * a reference to the file on disk. 
 * 
 * From the sqlite web-page: "A database file might contain one or more pages
 * that are not in active use. Unused pages can come about, for example, when
 * information is deleted startRegion the database. Unused pages are stored on the
 * freelist and are reused when additional pages are required."
 * 
 * This class makes use of this behavior.
 * 
 * 
 * 
 *     __________    __    _ __     
 *    / ____/ __ \  / /   (_) /____ 
 *   / /_  / / / / / /   / / __/ _ \
 *  / __/ / /_/ / / /___/ / /_/  __/
 * /_/    \___\_\/_____/_/\__/\___/ 
 * 
 * 
 * 
 * @author Dirk Pawlaszczyk
 * @version 1.2
 */
public class Job extends Base {
	
	/* since version 1.2 - support for write ahead logs WAL */
	public boolean readWAL = false;
	String walpath = null;
	WALReaderBase wal = null;

	/* since version 1.2 - support for write Rollback Journal files */
	public boolean readRollbackJournal = false;
	public boolean collectInternalRows = true;
	String rollbackjournalpath = null;
	RollbackJournalReaderBase rol = null;
	
	
    /* some constants */
	final static String MAGIC_HEADER_STRING = "53514c69746520666f726d6174203300";
	final static String NO_AUTO_VACUUM = "00000000";
	final static String NO_MORE_ENTRIES = "00000000";
	final static String LEAF_PAGE = "0d";
	final static String INTERIOR_PAGE = "05";
	final static String ROW_ID = "00";
	
	
	/* path - used to locate a file in a file system */
	String path;
	
	/* A channel for reading, writing, and manipulating the database file. */
	public RandomAccessFileReader file;
	
	/* this field represent the database encoding */
	public Charset db_encoding = StandardCharsets.UTF_8;
	
	/* this is a multi-threaded program -> all data are saved to the list first*/
	private Queue<SqliteInternalRow> ll = new ConcurrentLinkedQueue<>();

	private Map<String, List<SqliteRow>> tableRows = new LinkedHashMap<>();
	
	private Map<String, Map<String, Integer>> colIdxMaps = new HashMap<>();
	
	private Auxiliary aux = new Auxiliary(this);
	
	/* header fields */
	
	String headerstring;
	byte ffwversion;
	byte ffrversion;
	byte reservedspace;
	byte maxpayloadfrac;
	byte minpayloadfrac;
	byte leafpayloadfrac;
	long filechangecounter;
	long inheaderdbsize;
	long sizeinpages;
	long schemacookie;
	long schemaformatnumber;
	long defaultpagecachesize;
	long userversion;
	long vacuummode;
	long versionvalidfornumber;
	long avacc;
	
	/* Virtual Tables */
	public Map<String, TableDescriptor> virtualTables = new LinkedHashMap<String,TableDescriptor>();
	
	int scanned_entries = 0;
	
	Hashtable<String, String> tblSig;
	boolean is_default = false;
	public Map<String, TableDescriptor> headers = new LinkedHashMap<String, TableDescriptor>();
	public Map<String, IndexDescriptor> indices = new LinkedHashMap<String, IndexDescriptor>();
	public AtomicInteger runningTasks = new AtomicInteger();
	
	AtomicInteger numberofcells = new AtomicInteger();
	
	Set<Integer> allreadyvisit;

	/* pages on the freelist (trunk and leaf pages) - they do not belong to any b-tree */
	private Set<Integer> freelistPages = ConcurrentHashMap.newKeySet();
	
	/* this array holds a description of the associated component for each data page, if known */
	public AbstractDescriptor[] pages;
	
	/* each db-page has only one type */
	int[] pagetype;
	
	/* page size */
	public int ps = 0;
	public int numberofpages = 0;
	/* free page pragma fields in db header */
	int fpnumber = 0;
	int fphead = 0;
	String sqliteversion = "";
	boolean autovacuum = false;
	
	/* if a page has been scanned it is marked as checked */
	AtomicReferenceArray<Boolean> checked;
	
	public AtomicInteger hits = new AtomicInteger();
	
	/** Collect resources to close at end of processing */
	private Deque<Closeable> resourcesToClose = new LinkedList<>();

	public boolean recoverOnlyDeletedRecords = false;

	public SQLiteSchemaParser schemaParser = new SQLiteSchemaParser();
	
	private Object lock = new Object();

	  
	/******************************************************************************************************/
	
	/**
	 * Search for schema entries (sqlite_master records) in every page stored in the WAL archive.
	 */
	private void readDBSchemaFromWAL(int goBack, ByteSeqSearcher btsearch, ByteSeqSearcher bisearch) throws IOException {
		String wp = walpath != null ? walpath : path + "-wal";
		Path p = Paths.get(wp);
		if (!Files.exists(p)) {
		    return;
		}

		try (RandomAccessFileReader walfile = new RandomAccessFileReader(p)) {
		    ByteBuffer walheader = walfile.allocateAndReadBuffer(0, 32);
		    if (walheader == null) {
		        return;
		    }
		    int walps = walheader.getInt(8);
		    if (walps == 1)
		        walps = 65536;
		    if (walps != ps) {
		        warning("Page size of WAL file (", walps, ") differs from database page size (", ps, ")");
		        return;
		    }
		    /* each frame: 24 bytes frame header + page */
		    for (long frame = 32; frame + 24 + ps <= walfile.size(); frame += 24 + ps) {
		        ByteBuffer page = walfile.allocateAndReadBuffer(frame + 24, ps);
		        if (page == null) {
		            break;
		        }
		        readDBSchema(new LongPositionByteBufferWrapper(page), goBack, btsearch, bisearch, true);
		    }
		}
	}
	
	protected void tableDescriptorReady(TableDescriptor td) {
	}
	
	protected void indexDescriptorReady(IndexDescriptor id) {
	}
	
	protected void unassignedTableCreated(TableDescriptor td) {
	}
	
	protected void linesReady() throws IOException {
	}
			
	/**
	 * This is the main processing loop of the program.
	 * 
	 * @return -1 if error, 0 if success
	 * @throws InterruptedException the InterruptedException
	 * @throws ExecutionException the ExecutionException
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public int processDB() throws InterruptedException, ExecutionException, IOException {

		allreadyvisit = ConcurrentHashMap.newKeySet();
		freelistPages.clear();
		List<TableDescriptor> recoveryTables = new LinkedList<>();

		try {

			Path p = Paths.get(path);

			/* First - try to analyze the db-schema */
			/*
			 * we have to do this before we open the database because of the concurrent
			 * access
			 */

			/* try to open the db-file in read-only mode */
			file = new RandomAccessFileReader(p);
			resourcesToClose.add(file);

			/* read header of the sqlite db - the first 100 bytes */
			ByteBuffer buffer = file.allocateAndReadBuffer(100);
			if (buffer == null) {
			    return -1;
			}

			/* The first 100 bytes of the database file comprise the database file header. 
			 * The database file header is divided into fields as shown by the table below. 
			 * All multibyte fields in the database file header are stored with the must 
			 * significant byte first (big-endian).
			 * 
			 * 0	16	The header string: "SQLite format 3\000"
			 * 16	 2	The database page size in bytes. Must be a power of two between 512 and 32768 inclusive, or the value 1 representing a page size of 65536.
			 * 18    1	File format write version. 1 for legacy; 2 for WAL.
		     * 19  	 1	File format read version. 1 for legacy; 2 for WAL.
			 * 20    1	Bytes of unused "reserved" space at the end of each page. Usually 0.
			 * 21	 1	Maximum embedded payload fraction. Must be 64.
			 * 22 	 1	Minimum embedded payload fraction. Must be 32.
			 * 23 	 1	Leaf payload fraction. Must be 32.
			 * 24  	 4	File change counter.
			 * 28 	 4	Size of the database file in pages. The "in-header database size".
			 * 32  	 4	Page number of the first freelist trunk page.
			 * 36	 4	Total number of freelist pages.
			 * 40 	 4	The schema cookie.
			 * 44 	 4	The schema format number. Supported schema formats are 1, 2, 3, and 4.
			 * 48	 4	Default page cache size.
			 * 52	 4	The page number of the largest root b-tree page when in auto-vacuum or incremental-vacuum modes, or zero otherwise.
			 * 56	 4	The database text encoding. A value of 1 means UTF-8. A value of 2 means UTF-16le. A value of 3 means UTF-16be.
			 * 60	 4	The "user version" as read and set by the user_version pragma.
			 * 64	 4	True (non-zero) for incremental-vacuum mode. False (zero) otherwise.
			 * 68	24	Reserved for expansion. Must be zero.
			 * 92	 4	The version-valid-for number.
			 * 96	 4	SQLITE_VERSION_NUMBER
			 */
			
			
			/********************************************************************/

			byte header[] = new byte[16];
			buffer.get(header);
			headerstring = Auxiliary.bytesToHex(header);
			char charArray[] = new char[16];
			
			int cn = 0;
			for (byte b: header)
			{
				charArray[cn] = (char)b;
				cn++;
			}
			String txt = new String(charArray);
			
			headerstring = txt + " (" + "0x" + headerstring + ")";
			
			if (Auxiliary.bytesToHex(header).equals(MAGIC_HEADER_STRING)) // we currently
			{
				// support
				// sqlite 3 data
				// bases
				info("header is okay. seems to be an sqlite database file.");
		    }
			else {
				info("sorry. doesn't seem to be an sqlite file. Wrong header.");
				err("Doesn't seem to be an valid sqlite file. Wrong header");
				return -1;
			}

			
			buffer.position(18);
			ffwversion = buffer.get();
			info("File format write version. 1 for legacy; 2 for WAL. ", ffwversion);

			buffer.position(19);
			ffrversion = buffer.get();
			info("File format read version. 1 for legacy; 2 for WAL. ", ffrversion);

			buffer.position(20);
		    reservedspace = buffer.get();
			info("Bytes of unused \"reserved\" space at the end of each page. Usually 0. ", reservedspace);

			maxpayloadfrac = buffer.get();
			info("Maximum embedded payload fraction. Must be 64.", maxpayloadfrac);

			minpayloadfrac = buffer.get();
			info("Minimum embedded payload fraction. Must be 32.", minpayloadfrac);

			leafpayloadfrac = buffer.get();
			info("Leaf payload fraction. Must be 32.  ", leafpayloadfrac);

			/* offset 28: size of the database file in pages (the "in-header database size") */
			inheaderdbsize = Integer.toUnsignedLong(buffer.getInt(28));
			
			buffer.position(24);
			filechangecounter = Integer.toUnsignedLong(buffer.getInt());
			info("File change counter ", filechangecounter);

			buffer.position(28);
			sizeinpages = Integer.toUnsignedLong(buffer.getInt());
			info("Size of the database file in pages ", sizeinpages);

			buffer.position(40);
			schemacookie = Integer.toUnsignedLong(buffer.getInt());
			info("The schema cookie. (offset 40) ", schemacookie);

		    schemaformatnumber = Integer.toUnsignedLong(buffer.getInt());
			info("The schema format number. (offset 44) ", schemaformatnumber);
 
			defaultpagecachesize = Integer.toUnsignedLong(buffer.getInt());
			info("Default page cache size. (offset 48) ", defaultpagecachesize);
 
			buffer.position(60);
			
			userversion = Integer.toUnsignedLong(buffer.getInt());
			info("User version (offset 60) ", userversion);
 
			 
			vacuummode = Integer.toUnsignedLong(buffer.getInt());
			info("Incremential vacuum-mode (offset 64) ", vacuummode);
 
			buffer.position(92);

			versionvalidfornumber = Integer.toUnsignedLong(buffer.getInt());
			info("The version-valid-for number.  ", versionvalidfornumber);
 
			
					
			/********************************************************************/

			is_default = true;
			tblSig = new Hashtable<String, String>();
			tblSig.put("", "default");
			info("found unkown sqlite-database.");

			/********************************************************************/
			
			buffer.position(52);
			avacc = Integer.toUnsignedLong(buffer.getInt());
			/* largest root b-tree page: non-zero in auto-vacuum or incremental-vacuum mode */
			if (avacc == 0) {
				info("Seems to be no AutoVacuum db. Nice :-).");
				autovacuum = false;
			} else
				autovacuum = true;

			/********************************************************************/
			// Determine database text encoding.
			// A value of 1 means UTF-8. A value of 2 means UTF-16le. A value of
			// 3 means UTF-16be.
			byte[] encoding = new byte[4];
			buffer.position(56);
			buffer.get(encoding);
			int codepage = ByteBuffer.wrap(encoding).getInt();

			switch (codepage) {
			case 0:
			case 1:
				db_encoding = StandardCharsets.UTF_8;
				info("Database encoding: UTF_8");
				break;
			case 3:
				db_encoding = StandardCharsets.UTF_16BE;
				info("Database encoding: UTF_16BE");
				break;

			case 2:
				db_encoding = StandardCharsets.UTF_16LE;
				info("Database encoding: UTF_16LE");
				break;

			}

			/*******************************************************************/

			/* 2 Byte big endian value */
			byte pagesize[] = new byte[2];
			/* at offset 16 */
			buffer.position(16);
			buffer.get(pagesize);

			ByteBuffer psize = ByteBuffer.wrap(pagesize);
			/*
			 * Must be a power of two between 512 and 32768 inclusive, or the value 1
			 * representing a page size of 65536.
			 */
			ps = Auxiliary.TwoByteBuffertoInt(psize);

			/*
			 * Beginning with SQLite version 3.7.1 (2010-08-23), a page size of 65536 bytes
			 * is supported.
			 */
			if (ps == 0 || ps == 1)
				ps = 65536;

			info("page size ", ps, " Bytes ");

			/*******************************************************************/

			/*
			 * get file size: Attention! We use the real file size information startRegion
			 * the file object not the header information!
			 */
			long totalbytes = file.size();

			/*
			 * dividing the number of bytes by pagesize we can compute the number of pages.
			 */
			numberofpages = (int) (totalbytes / ps);

			info("Number of pages:", numberofpages);

			/*******************************************************************/

			/* determine the SQL-version of db on offset 96 */

			byte version[] = new byte[4];
			buffer.position(96);
			buffer.get(version);

			Integer v = ByteBuffer.wrap(version).getInt();
			sqliteversion = "" + v;

			/*******************************************************************/

			/* initialize some data structures */
			pages = new AbstractDescriptor[numberofpages + 1];
			pagetype = new int[numberofpages];
			checked = new AtomicReferenceArray<Boolean>(new Boolean[numberofpages]);

			/*******************************************************************/

			// byte[] pattern = null;
			byte[] tpattern = null;
			byte[] ipattern = null;
			int goback = 0;

			/* there are 3 possible encodings */
			if (db_encoding == StandardCharsets.UTF_8) {
				// pattern = new byte[]{7, 23};
				/* we are looking for the word 'table' */
				tpattern = new byte[] { 116, 97, 98, 108, 101 };
				/* we are looking for the word 'index' */
				ipattern = new byte[] { 105, 110, 100, 101, 120 };
				goback = 11;

			} else if (db_encoding == StandardCharsets.UTF_16LE) {
				/* we are looking for the word 'table' coding with UTF16LE */
				tpattern = new byte[] { 116, 00, 97, 00, 98, 00, 108, 00, 101 };
				/* we are looking for the word 'index' */
				ipattern = new byte[] { 105, 00, 110, 00, 100, 00, 101, 00, 120 };

				goback = 15;

			} else if (db_encoding == StandardCharsets.UTF_16BE) {
				/* we are looking for the word 'table' coding with UTF16BE */
				tpattern = new byte[] { 00, 116, 00, 97, 00, 98, 00, 108, 00, 101 };
				/* we are looking for the word 'index' */
				ipattern = new byte[] { 00, 105, 00, 110, 00, 100, 00, 101, 00, 120 };
				goback = 16;

			}
			
			final int goBack = goback;

			
	
			
			/* we looking for the key word <table> of the type column */
			/* the mark the start of an entry for the sqlmaster_table */
			final ByteSeqSearcher btsearch = new ByteSeqSearcher(tpattern);
			/* we looking for the key word <index> */
            /* to mark the start of an indices entry for the sqlmaster_table */
            final ByteSeqSearcher bisearch = new ByteSeqSearcher(ipattern);
			
			// try to read DB schema following btree.
			exploreBTree(1,
			        pageNumber -> {
			            ByteBuffer page = readPageWithNumber(pageNumber - 1, ps);
			            if (page != null) {
			                readDBSchema(new LongPositionByteBufferWrapper(page),
			                    goBack, btsearch, bisearch, true);
			            }
			        }
			);

			/*
			 * 1. Try to read table and index information from the whole database file
			 *    (this finds deleted schema entries too)
			 *
			 * 2. No information found but a WAL archive in place - try to get the
			 *    missing information from the pages stored in the WAL archive
			 */
			readDBSchema(file, goback, btsearch, bisearch, false);
			info("headers:::: ", headers.size());

			if (headers.size() == 0 && readWAL) {
			    info("Could not find a schema definition inside the main db-file. Try to find something inside the WAL archive");
			    readDBSchemaFromWAL(goBack, btsearch, bisearch);
			}

			/*******************************************************************/

			/*
			 * Since we now have all component descriptors - let us update the 
			 * indices descriptors
			 */

			
			for (IndexDescriptor id : indices.values()) {
				String tbn = id.tablename;
				
				if (null != tbn) {
				    TableDescriptor td = headers.get(tbn);

				    if (null != td) {

						/* component for index could be found */
						List<String> idname = id.columnnames;
						List<String> tdnames = td.columnnames;

						/* we need to find the missing column types - one type per index column */
						for (int z = 0; z < idname.size(); z++) {
							int match = tdnames.indexOf(idname.get(z));
							String type = null;
							if (match >= 0 && match < td.getColumntypes().size()) {
								type = td.getColumntypes().get(match);
							}
							/* expression or unknown column: NUMERIC matches any value */
							id.columntypes.add(type == null || type.isEmpty() ? "NUMERIC" : type);
							debug("ADDING IDX TYPE::: ", type, " FOR TABLE ", td.tblname, " INDEx ", id.idxname);
						}

						if (td.ROWID) {
							/**
							 * The index contains data from the columns that you specify in the index and
							 * the corresponding rowid value. This helps SQLite quickly locate the row based
							 * on the values of the indexed columns.
							 */
							id.columnnames.add("rowid");
							id.columntypes.add("INT");
						} else {
							/* WITHOUT ROWID table: the index holds the primary key columns instead of the rowid */
							for (String pk : td.primarykeycolumns) {
								if (!id.columnnames.contains(pk)) {
									int match = tdnames.indexOf(pk);
									String type = (match >= 0 && match < td.getColumntypes().size()) ? td.getColumntypes().get(match) : null;
									id.columnnames.add(pk);
									id.columntypes.add(type == null || type.isEmpty() ? "NUMERIC" : type);
								}
							}
						}

						/* HeadPattern !!! */
						Auxiliary.addHeadPattern2Idx(id);
						
						exploreBTree(id.getRootOffset(), pageNumber -> {
                                if (null == pages[pageNumber])
                                    pages[pageNumber] = id;
                                }
                        );

						//break;
					}

				}
			}
			
			/*
			 * Now, since we have all component definitions, we can update the UI and start
			 * exploring the b-tree for each component
			 */
			for (TableDescriptor td : headers.values()) {

			    td.printTableDefinition();

				info(" root offset for component ", td.getRootOffset());

				String signature = td.getSignature();
				info(" signature ", signature);

				/* save component fingerprint for compare */
				if (signature != null && signature.length() > 0)
					tblSig.put(td.getSignature(), td.tblname);

				
				
				tableDescriptorReady(td);

				if (td.isVirtual())
					continue;

				/* transfer component information for later recovery */
				recoveryTables.add(td);

				/* explore a component trees and build up page info */
				exploreBTree(td.getRootOffset(), pageNumber -> { 
                        if (null == pages[pageNumber])
                            pages[pageNumber] = td;
                    }, !td.ROWID
                );

			}

			/*******************************************************************/

			for (IndexDescriptor id : indices.values()) {
				int r = id.getRootOffset();
				info(" root offset for index ", r);

				indexDescriptorReady(id);
			}
		
			

			/*******************************************************************/

			/**
			 * Sometimes, a record cannot assigned to a component or index -> these records
			 * are assigned to the __UNASSIGNED component.
			 */

			List<String> col = new ArrayList<String>();
			List<String> names = new ArrayList<String>();

			/* create dummy component for unassigned records */
			for (int i = 0; i < 20; i++) {
				col.add("TEXT");
				names.add("col" + (i + 1));
			}
			TableDescriptor tdefault = new TableDescriptor("__UNASSIGNED", "",col, col, names, null, null, null, false);
			headers.put(tdefault.getName(), tdefault);
			
			unassignedTableCreated(tdefault);
			/*******************************************************************/

			byte freepageno[] = new byte[4];
			buffer.position(36);
			buffer.get(freepageno);
			info("Total number of free list pages ", freepageno);
			ByteBuffer no = ByteBuffer.wrap(freepageno);
			fpnumber = no.getInt();
			info(" no ", fpnumber);

			/*******************************************************************/

			byte freelistpage[] = new byte[4];
			buffer.position(32);
			buffer.get(freelistpage);
			info("FreeListPage starts at offset ", freelistpage);
			int head = ByteBuffer.wrap(freelistpage).getInt();
			info("head:: ", head);
			fphead = head;

			/*******************************************************************/

			if (head == 0) {
				info("INFO: Couldn't locate any free pages to recover. ");
			}

			/*******************************************************************
			 *
			 * STEP 1: we start recovery process with scanning the free list first
			 * 
			 * A freelist trunk page has the following layout:
			 *   0  4  page number of the next trunk page (0 for the last one)
			 *   4  4  number of leaf page numbers on this trunk page (L)
			 *   8  4L leaf page numbers
			 **/

			if (head > 0 && head <= numberofpages) {

				long startfp = System.currentTimeMillis();
				info("Start free page recovery .....");

				/* create a new threadpool to analyze the freepages */
				ExecutorService executor = null;
				if (getNumberOfThreads() > 0) {
					executor = Executors.newFixedThreadPool(getNumberOfThreads());
				}

				int freepagesum = 0;
				int trunk = head;
				allreadyvisit.add(trunk);
				int maxEntries = ps / 4 - 2;

				while (trunk > 0) {
					freelistPages.add(trunk);
					/* reserve space for the first/next page of the free list */
					ByteBuffer fplist = readPageWithNumber(trunk - 1, ps);
					if (fplist == null) {
					    break;
					}

					/* the next trunk page */
					int nfp = fplist.getInt(0);

					/* now read the number of entries for this particular page */
					int entries = fplist.getInt(4);
					info(" Number of Entries in freepage list ", entries);
					if (entries < 0 || entries > maxEntries) {
						warning("Invalid number of entries in freelist trunk page ", trunk, ": ", entries);
						entries = entries < 0 ? 0 : maxEntries;
					}

					/* iterate through free page list and read free page offsets */
					for (int zz = 0; zz < entries; zz++) {
						int n = fplist.getInt(8 + 4 * zz);

						if (n <= 0 || n > numberofpages || !freelistPages.add(n)) {
							continue;
						}
						// determine offset for free page
						long offset = (long) (n - 1) * ps;

						RecoveryTask task = new RecoveryTask(aux, this, offset, n, ps, true, recoveryTables);
						/* add new task to executor queue */
						runningTasks.incrementAndGet();
						if (executor != null) {
							executor.execute(task);
						} else {
							/* errors are logged - a corrupted page must not stop the recovery */
							task.run();
						}
					}
					freepagesum += entries;

					/* is there a further trunk page? */
					if (nfp <= 0 || nfp > numberofpages) {
						trunk = 0;
					} else if (!allreadyvisit.add(nfp)) {
						info("Antiforensiscs found: cyclic freepage list entry");
						trunk = 0;
					} else {
						trunk = nfp;
					}
				}

				info("Task total: ", runningTasks.intValue());

				if (executor != null) {
					executor.shutdown();
					executor.awaitTermination(1000, TimeUnit.DAYS);
				}

				info("Number of cells ", numberofcells.intValue());

				info(" Finished. No further free pages. Scanned ", freepagesum);

				long endfp = System.currentTimeMillis();
				info("Duration of free page recovery in ms: ", (endfp - startfp));

			} // end of free page list recovery

			info("Lines after free page recovery: ", ll.size());

			/*******************************************************************/
			// start carving

			// full db-scan (including all database pages)
			scan(numberofpages, ps, recoveryTables);

			/*******************************************************************/
			
			if (readRollbackJournal) {
	            /* the readRollbackJournal option is enabled -> check the journal file too */
	            if (rollbackjournalpath == null)
	                rollbackjournalpath = path + "-journal";
	            Logger.out.info(" RollbackJournal-File ", this.rollbackjournalpath);
	            rol = new RollbackJournalReaderBase(rollbackjournalpath, this) {
                    @Override
                    public void output() {                        
                    }
	            };
	            rol.ps = this.ps;
	            rol.tables.addAll(recoveryTables);
	            /* start parsing Rollbackjournal-file */
	            rol.parse();
	        }
	        if (readWAL) {
	            /* the readWAL option is enabled -> check the WAL-file too */
	            if (walpath == null)
	                walpath = path + "-wal";
	            Logger.out.info(" WAL-File ", walpath);
	            wal = new WALReaderBase(walpath, this) {
                    @Override
                    public void output() {                        
                    }
                };
	            wal.tables.addAll(recoveryTables);
	            /* start parsing WAL-file */
	            wal.parse();
	        }
			
			linesReady();

		} finally {
			closeResources();
		}

		return 0;
	}
	
	private Map<String, Integer> getColIdxMapForTable(String tableName) {
	    synchronized (lock) {
    	    Map<String, Integer> colIdxMap = colIdxMaps.get(tableName);
    	    if (colIdxMap == null) {
    	        TableDescriptor td = headers.get(tableName);
    	        if (td != null) {
    	            colIdxMap = new HashMap<>();
    	            int id = 0;
    	            for (String col: td.columnnames) {
    	                colIdxMap.put(col, id++);
    	            }
    	            colIdxMaps.put(tableName, colIdxMap);
    	        }
    	    }
	    return colIdxMap;
	    }
	}

	protected void closeResources() {
	    for (Closeable c : resourcesToClose) {
            try {
                c.close();
            } catch (IOException e) {
                err(e);
            }
        }
	    resourcesToClose.clear();
	}

	/* number of bytes read before the text of the type column of a schema record */
	private static final int SCHEMA_HEADER_BYTES = 9;

	private void readDBSchema(LongPositionByteBuffer bb, int goback, ByteSeqSearcher btsearch, ByteSeqSearcher bisearch, boolean isSinglePage) throws IOException {
	    for (ByteSeqSearcher bsearch : new ByteSeqSearcher[] {btsearch, bisearch}) {
            
            long index = bsearch.indexOf(bb, 0);

            /* search as long as we can find further table keywords */
            while (index != -1) {
                /*
                 * the record header ends where the text of the type column ('table'/'index') starts;
                 * read the bytes before the text (payload length, rowid and header)
                 */
                long textstart = index - bsearch.patternLength();
                long headerpos = textstart - SCHEMA_HEADER_BYTES;
                if (headerpos < 0) {
                    /* not enough bytes before the keyword for a record header */
                    index = bsearch.indexOf(bb, index + 2);
                    continue;
                }
                bb.position(headerpos);
                int hsize = Math.min(40, (int) (bb.size() - bb.position()));
                if (hsize < 20) {
                    break;
                }
                byte mheader[] = new byte[hsize];

                bb.get(mheader);

                int starthere = -1;
                int headerSize = 0;
                /* position of the bytes read relative to the page containing the text */
                long page = textstart / ps;
                int headerposInPage = (int) (headerpos - page * ps);
                /* 'table' and 'index' have 5 characters */
                int typeLength = (db_encoding == StandardCharsets.UTF_8) ? 5 : 10;
                int [] valData = aux.validateIntactMasterTableHeader(mheader, 0, SCHEMA_HEADER_BYTES, typeLength);
                int headerStart = valData[0];
                if (headerStart != Integer.MIN_VALUE) {
                    headerSize = valData[1];
                    /* start of the serial types (behind the header size byte) */
                    headerStart += 1;
                    starthere = headerposInPage + headerStart;
                } else {
                    /* the header size (and the bytes before) were overwritten - e.g. a deleted record */
                    valData = aux.validateOverwrittenMasterTableHeader(mheader, SCHEMA_HEADER_BYTES, typeLength);
                    headerStart = valData[0];
                    if (headerStart != Integer.MIN_VALUE) {
                        headerSize = valData[1];
                        headerStart += 1;
                        starthere = headerposInPage + headerStart;
                    }
                }

                if (starthere >= 0) {
                    ByteBuffer bbb;
                    if (isSinglePage) {
                        bbb = bb.allocateAndReadBuffer(0, ps);
                    } else {
                        bbb = readPageWithNumber(page, ps);
                    }
                    if (bbb == null) {
                        break;
                    }
                    try {
                        /* start reading the schema string from the correct position */
                        aux.readMasterTableRecord(starthere, bbb, headerSize);
                    } catch (RuntimeException e) {
                        debug("Could not read schema record at ", index, ": ", e);
                    }
                }
                /* search for more component entries */
                index = bsearch.indexOf(bb, index + 2);
            }
        }
	}

	/**
	 * The method creates a Task object for each page to be scanned.
	 * Each task is then scheduled into a worker thread's ToDo list. 
	 * 
	 * After all tasks are assigned, the worker threads are started 
	 * and begin processing.  
	 * 
	 * @param number the number
	 * @param ps the ps
	 * 
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	private void scan(int number, int ps, List<TableDescriptor> recoveryTables) throws IOException {
		info("Start with scan...");
		/* create a new threadpool to analyze the freepages */
		ExecutorService executor = null;
		if (getNumberOfThreads() > 0) {
			executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(getNumberOfThreads());
		}

		long begin = System.currentTimeMillis();

		List<RecoveryTask> tasks = new LinkedList<>();
        
		
		for (int cc = 1; cc < pages.length; cc++) {
			
			if (null == pages[cc] || freelistPages.contains(cc)) 
			{
				debug("page ", cc, " is no regular leaf page component. Maybe a indices or overflow or dropped component page.");
				//System.out.println("page " + cc + " offset " + ((cc-1)*ps) + " is no regular leaf page component. Maybe a indices or overflow or dropped component page.");
			} 
			else 
			{
				debug("page ", cc, " is a regular leaf page. ");

				// determine offset for free page
				long offset = (long) (cc - 1) * ps;

				//System.out.println("************************ pagenumber " + cc + " " + offset);

				RecoveryTask task = new RecoveryTask(aux, this, offset, cc, ps, false, recoveryTables);
				tasks.add(task);
				runningTasks.incrementAndGet();
			}
		}
		if (executor != null) {
			debug("Task total: ", runningTasks.intValue(), " worker threads ", getNumberOfThreads());
		}
		
		int c = 1;
		/* start executing the work threads */
		for (RecoveryTask t : tasks)
		{	
			if (executor != null) {
				info(" Start worker thread", c++);
				/* add new task to executor queue */
				executor.execute(t);
			} else {
				/* errors are logged - a corrupted page must not stop the recovery */
				t.run();
			}
		}

		try {
		    // System.out.println("attempt to shutdown executor");
			if (executor != null) {
		    	executor.shutdown();
				executor.awaitTermination(10, TimeUnit.DAYS);
			}		
		}
		catch (InterruptedException e) {
		    err("tasks interrupted");
		}
		finally {
			if (executor != null) {
				if (!executor.isTerminated()) {
					getLogger().error("cancel non-finished tasks");
				}
				executor.shutdownNow();
				info("shutdown finished");
			}
		}

		long ende = System.currentTimeMillis();
		info("Duration of scanning all pages in ms : ", (ende-begin));
		info("End of Scan...");
		
		

	}

	/**
	 * @return the usable size of a database page, i.e. the page size minus the
	 *         reserved space at the end of each page
	 */
	public int getUsablePageSize() {
		return ps - (reservedspace & 0xFF);
	}

	/* number of worker threads of this job, null: use Global.numberofThreads */
	private Integer numberOfThreads = null;

	/**
	 * Set the number of worker threads used by this job (0: run in the calling thread).
	 * By default, {@link Global#numberofThreads} is used.
	 * 
	 * @param numberOfThreads the number of worker threads
	 */
	public void setNumberOfThreads(int numberOfThreads) {
		this.numberOfThreads = numberOfThreads;
	}

	/**
	 * @return the number of worker threads used by this job
	 */
	public int getNumberOfThreads() {
		return numberOfThreads != null ? numberOfThreads : Global.numberofThreads;
	}

	public void setPath(String path) {
		this.path = path;
	}
	
	public void setWALPath(String path) {
		walpath = path;
	}
	
	public void setRollbackJournalPath(String path)
	{
		rollbackjournalpath = path;
	}
	

	/**
	 *  This method is used to start the job. 
	 */
	public void start() {
		if (path != null)
			try {
				run(path);
			} catch (Exception e) {
				/* always reported (independent of LOGLEVEL), like the stack trace printed before */
				getLogger().error("Error while processing " + path, e);
			}
		else
			return;
	}
		
	/**
	 *	 
	 */
	public Job() {
		Base.LOGLEVEL = Global.LOGLEVEL;
	}


	/**
	 * Start processing a new Sqlite file.
	 * 
	 * @param p path to the Sqlite file
	 * @return 0 if successful, -1 if not
	 */
	public int run(String p) {
		
		int hashcode = -1;
		path = p;
		long start = System.currentTimeMillis();
		try {
			hashcode = processDB();
		} catch (InterruptedException | ExecutionException | IOException e) {
			/* always reported (independent of LOGLEVEL), like the stack trace printed before */
			getLogger().error("Error while processing " + p, e);
		}
		long end = System.currentTimeMillis();
		info("Duration in ms: ", (end - start));

		return hashcode;
	}

	/**
	 * Read a database page from a given offset with a fixed pagesize.
	 * 
	 * @param offset the offset
	 * @param pagesize the pagesize
	 * @return  A <code>ByteBuffer</code> object containing the page content. 
	 * @throws IOException if an error occurs while reading the page.
	 */
	public ByteBuffer readPageWithOffset(long offset, int pagesize) throws IOException {

		if ((offset > file.size()) || (offset < 0))
		{
			
			info(" offset greater than file size ?!", offset, " > ", file.size());
			Auxiliary.printStackTrace();
			
			
			return null;
		}
		return file.allocateAndReadBuffer(offset, pagesize);
	}

	/**
	 *  Since all pages are assigned to a unique number in SQLite, we can read a 
	 *  page by using this value together with the pagesize. 
	 *  
	 * @param pagenumber (&gt;=1)
	 * @param pagesize size of page
	 * @return  A <code>ByteBuffer</code> object containing the page content. 
	 * @throws IOException if an error occurs while reading the page.
	 */
	public ByteBuffer readPageWithNumber(long pagenumber, int pagesize) throws IOException {
		
		if (pagenumber < 0)
		{
		   return null;
		}
		return readPageWithOffset(pagenumber * (long) pagesize, pagesize);
	}	
	
	/**
     * The B-tree , or, more specifically, the B+-tree, 
     * is the most widely used physical database structure 
     * for primary and secondary indexes on database relations. 
     * 
     * This method can be called to traverse all nodes of 
     * a table-tree or an index-tree. Every page is visited at most once,
     * so corrupted trees with cycles can not lead to an endless loop.
     * 
     * @param rootPage  the page number of the root page
     * @param visitor   visitor to act on leaf pages 
     * @throws IOException ioException
     */
    private void exploreBTree(int rootPage, BTreePageVisitor visitor) throws IOException {
        exploreBTree(rootPage, visitor, false);
    }

    /**
     * Traverse a b-tree.
     * 
     * @param rootPage      the page number of the root page
     * @param visitor       visitor to act on leaf pages
     * @param indexInterior true to descend into interior pages of index b-trees and to visit them too:
     *                      the b-tree of a WITHOUT ROWID table is an index b-tree, its interior pages
     *                      hold records as well
     * @throws IOException ioException
     */
    private void exploreBTree(int rootPage, BTreePageVisitor visitor, boolean indexInterior) throws IOException {

        Deque<Integer> stack = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        stack.push(rootPage);

        while (!stack.isEmpty()) {
            int pageNumber = stack.pop();

            if (pageNumber <= 0 || pageNumber > numberofpages || !visited.add(pageNumber))
                continue;

            ByteBuffer buffer = readPageWithNumber(pageNumber - 1, ps);
            if (buffer == null)
                continue;

            int pageHeaderOffset = (pageNumber == 1) ? 100 : 0;

            /* check type of the page by reading the first byte */
            int typ = Auxiliary.getPageType(buffer.get(pageHeaderOffset));

            if (typ == 12 || (typ == 2 && indexInterior)) {
                /* interior page of a table (12) or of an index (2) */
                debug("page number ", pageNumber, " is an interior page ");
                if (typ == 2)
                    visitor.visitLeafPage(pageNumber);

                int e = buffer.getShort(pageHeaderOffset + 3) & 0xFFFF;
                List<Integer> children = new ArrayList<>();

                /* go on with the cell pointer array */
                for (int i = 0; i < e; i++) {
                    int pointer = pageHeaderOffset + 12 + 2 * i;
                    if (pointer + 2 > buffer.limit())
                        break;
                    int celloff = buffer.getShort(pointer) & 0xFFFF;
                    /* each cell starts with the page number of the left child */
                    if (celloff + 4 > buffer.limit())
                        continue;
                    children.add(buffer.getInt(celloff));
                }
                /* the right most child pointer is part of the page header */
                children.add(buffer.getInt(pageHeaderOffset + 8));

                /* visit the children in their order */
                for (int i = children.size() - 1; i >= 0; i--)
                    stack.push(children.get(i));
            }
            else if (typ == 8 || typ == 10) {
                debug("page number ", pageNumber, " is a leaf page set component/index to sqlite schema ");
                visitor.visitLeafPage(pageNumber);
            }
        }
    }



	/**
	 * Return the columnnames as a String array for a given table or indextable name.
	 * @param tablename name of the table or index
	 * @return the headerString
	 */
	public String[] getHeaderString(String tablename)
	{
		/* check tables first */
	    TableDescriptor td = headers.get(tablename);
	    if (null != td) {
	        return td.columnnames.toArray(new String[0]);
	    }

		/* check indicies next */
	    IndexDescriptor id = indices.get(tablename);
	    if (null != id) {
	        return id.columnnames.toArray(new String[0]);			
		}

		/* no luck */
		return null;
	}
	
	/**
     * Save findings into a comma separated file.
     * @param filename file to write results to
     * @param lines lines to write
     */
    public void writeResultsToFile(String filename, String [] lines) {
        info("Write results to file...");
        info("Number of records recovered: ", ll.size());

        if (null == filename) {
            Path dbfilename = Paths.get(path);
            String name = dbfilename.getFileName().toString();

            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter df;
            df = DateTimeFormatter.ISO_DATE_TIME; // 2020-01-31T20:07:07.095
            String date = df.format(now);
            date = date.replace(":","_");

            filename = "results" + name + date + ".csv";
        }
        
        /* do not change the order of the array of the caller */
        lines = Arrays.copyOf(lines, lines.length);
        Arrays.sort(lines);

        /** convert line to UTF-8 **/
        try {
            
            final File file = new File(filename);
            
            
            try (final BufferedWriter writer = Files.newBufferedWriter(file.toPath(),StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) 
            {
              for (String line: lines)
              { 
                  writer.write(line);
              }
            }
            
        } catch (IOException e) {
            getLogger().error("Could not write the results to " + filename, e);
        }

    }

    public void addRow(SqliteInternalRow row) {
        row.setColumnNamesMap(getColIdxMapForTable(row.getTableName()));
		if (recoverOnlyDeletedRecords) {
			// only add rows that are marked as deleted
			if (!row.isDeletedRow()) {
				return;
			}
		}
		if (collectInternalRows)
		    ll.add(row);
		synchronized (lock) {
            List<SqliteRow> tables = tableRows.get(row.getTableName());
            if (null == tables) {
                tables = new ArrayList<>();
                tableRows.put(row.getTableName(), tables);
            }
            SqliteRow decoded = row.decodeRow();
            decoded.setCharset(db_encoding);
            tables.add(decoded);
		}
    }

    public Queue<SqliteInternalRow> getRows() {
        return ll;
    }

    public List<SqliteRow> getRowsForTable(String tableName) {
        synchronized (lock) {
            /* a copy - the recovery may still add rows (WAL/journal readers, worker threads) */
            return new ArrayList<>(tableRows.getOrDefault(tableName, Collections.emptyList()));
        }
    }

    public synchronized Set<String> getTablesNames() {
        synchronized (lock) {
            return new LinkedHashSet<>(tableRows.keySet());
        }
    }
    
    private interface BTreePageVisitor {
        void visitLeafPage(int pageNumber) throws IOException;
    }
}
