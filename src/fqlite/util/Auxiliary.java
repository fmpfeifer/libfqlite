package fqlite.util;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import fqlite.base.Base;
import fqlite.base.Global;
import fqlite.base.Job;
import fqlite.base.SqliteElement;
import fqlite.base.SqliteElementData;
import fqlite.base.SqliteInternalRow;
import fqlite.descriptor.AbstractDescriptor;
import fqlite.descriptor.IndexDescriptor;
import fqlite.descriptor.TableDescriptor;
import fqlite.pattern.HeaderPattern;
import fqlite.pattern.IntegerConstraint;
import fqlite.types.SerialTypes;
import fqlite.types.StorageClasses;
import java.nio.Buffer;

/**
 * This class offers a number of useful methods that are needed from time to
 * time for the acquisition process.
 * 
 * @author pawlaszc
 *
 */
public class Auxiliary extends Base {
	private static final int TABLELEAFPAGE = 0x0d;
	private static final int TABLEINTERIORPAGE = 0x05;
	private static final int INDEXLEAFPAGE = 0x0a;
	private static final int INDEXINTERIORPAGE = 0x02;
	private static final int OVERFLOWPAGE = 0x00;
	
	private Job job;
	
	/**
     * Constructor. To return values to the calling job environment, an object
     * reference of job object is required.
     * 
     * @param job the job object
     */
    public Auxiliary(Job job) {
        this.job = job;
    }

	/**
	 * Get the type of page. There are 4 different basic types in SQLite: (1)
	 * component-leaf (2) component-interior (3) indices-leaf and (4)
	 * indices-interior.
	 * 
	 * Beside this, we can further find overflow pages and removed pages. Both start
	 * with the 2-byte value 0x00.
	 * 
	 * @param byteType the byte containing the page type
	 * @return type of page
	 */
	public static int getPageType(byte byteType) {

		boolean skip = false;
		switch (byteType) {

		case TABLELEAFPAGE:
			return 8;

		case TABLEINTERIORPAGE:
			return 12;

		case INDEXLEAFPAGE:
			skip = true;
			return 10;

		case INDEXINTERIORPAGE:
			skip = true;
			return 2;

		case OVERFLOWPAGE: // or dropped page
			return 0;

		default:
			skip = true;
		}
		if (skip) {
			return -1;
		}

		return -99;
	}

	/**
	 * An important step in data recovery is the analysis of the database schema.
	 * This method allows to read in the schema description into a ByteBuffer.
	 * 
	 * @param start the start position
	 * @param buffer the buffer to be read
	 * @param headerSize the size of the header
	 * @return true if successful
	 * @throws IOException if an I/O error occurs
	 */
	public boolean readMasterTableRecord(int start, ByteBuffer buffer, int headerSize) throws IOException {

		SqliteElement[] columns;

		buffer.position(start);
		byte[] headerBytes = BufferUtil.allocateByteBuffer(headerSize - 1);
		buffer.get(headerBytes);

		columns = convertHeaderToSqliteElements(headerBytes, job.db_encoding);

		if (null == columns)
			return false;

		int con = 0;

		String tablename = null;
		int rootpage = -1;
		String statement = null;

		/* start reading the content */
		for (SqliteElement en : columns) {

			if (en == null) {
				/* reserved serial type - this is no valid sqlite_master record */
				return false;
			}

			byte[] value = BufferUtil.allocateByteBuffer(en.length);

			try
			{
				buffer.get(value);
			}
			catch(BufferUnderflowException bue)
			{
				return false;
			}

			/* column 2 ? -> tbl_name TEXT */
			if (con == 2) {
				tablename = en.toString(value);
			}

			/* column 3 ? -> root page Integer */
			if (con == 3) {
				if (value.length == 0 && en.type != SerialTypes.INT0 && en.type != SerialTypes.INT1)
					debug("Seems to be a virtual component -> no root page ;)");
				else {
					/* the root page can be stored with any integer serial type (1 to 4 bytes) */
					long root = new SqliteElementData(en, value).getIntValue();
					/* virtual tables have root page 0 */
					if (root >= 0 && root <= Integer.MAX_VALUE)
						rootpage = (int) root;
				}
			}

			/* read sql statement */

			if (con == 4) {
				statement = en.toString(value);
				break; // do not go further - we have everything we need
			}

			con++;

		}

		// finally, we have all information in place to parse the CREATE statement
		if (tablename == null || statement == null || rootpage < 0)
		    return false;
		
		job.schemaParser.parse(job, tablename, rootpage, statement);

		return true;
	}

	/**
	 * This method is used to extract a previously deleted record in unused space a
	 * page.
	 * 
	 * @param job the job object
	 * @param start      the exact position (offset relative to the page start).
	 * @param buffer     a ByteBuffer with the data page to analyze.
	 * @param header     the record header bytes including header length and serial
	 *                   types.
	 * @param bs         a data structure that is used to record which areas have
	 *                   already been searched
	 * @param pagenumber the number of the page we going to analyze
	 * @return the CarvingResult
	 * @throws IOException if something went wrong during read-up.
	 */
	public CarvingResult readDeletedRecord(Job job, int start, ByteBuffer buffer, String header, BitSet bs,
			int pagenumber) throws IOException {

		SqliteElement[] columns;

		buffer.position(start);

		int recordstart = start - (header.length() / 2);

		/* skip the header length byte */
		header = header.substring(2);

		columns = toColumns(header, job.db_encoding);

		if (null == columns)
			return null;

		SqliteInternalRow row = new SqliteInternalRow();
		//int co = 0;
		String fp = null;
		try {
			fp = getTableFingerPrint(columns);

		} catch (NullPointerException err) {
			// System.err.println(err);
		}
		if (null == fp)
			fp = "unkown";

		boolean error = false;

		row.setOffset((long) (pagenumber - 1) * job.ps + buffer.position());

		/* use the header information to reconstruct */
		int pll = computePayloadLength(header);

		int so;
		so = computePayload(pll);

		int overflow = -1;

		if (so < pll) {
			int phl = header.length() / 2;

			int last = buffer.position();
			debug(" deleted spilled payload ::", so);
			debug(" deleted pll payload ::", pll);
			buffer.position(buffer.position() + so - phl - 1);

			overflow = buffer.getInt();
			debug(" deleted overflow::::::::: ", overflow, " ", Integer.toHexString(overflow));
			buffer.position(last);

			ByteBuffer bf;

			/* is the overflow page value correct ? */
			if (overflow > 0 && overflow  < job.numberofpages) {

				/*
				 * we need to increment page number by one since we start counting with zero for
				 * page 1
				 */
				byte[] extended = readOverflowIterativ(overflow - 1);

				byte[] c = BufferUtil.allocateByteBuffer(pll + job.ps);

				buffer.position(0);
				byte[] originalbuffer = BufferUtil.allocateByteBuffer(job.ps);
				for (int bb = 0; bb < job.ps; bb++) {
					originalbuffer[bb] = buffer.get(bb);
				}

				//buffer.position(last);

                /* copy spilled overflow of current page into extended buffer */
                //System.arraycopy(originalbuffer, buffer.position(), c, 0, so + 7);
                /* append the rest startRegion the overflow pages to the buffer */
                //System.arraycopy(extended, 0, c, so - phl -1, extended.length - so);
                //bf = ByteBuffer.wrap(c);


                buffer.position(last);
                bf = null;

                /* copy spilled overflow of current page into extended buffer */
                System.arraycopy(originalbuffer, buffer.position(), c, 0, so + 7 );  // - phl
                /* append the rest startRegion the overflow pages to the buffer */
                try {
                    if (null != extended)
                        // copy every byte from extended (beginning with index 0) into byte-array c, at position so-phl
                        System.arraycopy(extended, 0, c, so - phl - 1, pll - so);
                        bf = ByteBuffer.wrap(c);
                } catch (ArrayIndexOutOfBoundsException err) {
                    info("Error IndexOutOfBounds");
                } catch (NullPointerException err2) {
                    info("Error NullPointer in ");
                }

			} else {
				pll = so;
				bf = buffer;
			}
			bf.position(0);

			/* start reading the content */
			for (SqliteElement en : columns) {
				if (en == null) {
					continue;
				}

				byte[] value = BufferUtil.allocateByteBuffer(en.length);

				bf.get(value);

				row.append(new SqliteElementData(en, value));

				//co++;
			}

			// set original buffer pointer to the end of the spilled payload
			// just before the next possible record
			buffer.position(last + so - phl - 1);

		} else {

			for (SqliteElement en : columns) {

				if (en == null) {
					continue;
				}

				byte[] value = BufferUtil.allocateByteBuffer(en.length);
				if ((buffer.position() + en.length) > buffer.limit()) {
					error = true;
					return null;
				}
				buffer.get(value);

				row.append(new SqliteElementData(en, value));

				//co++;

			}

			if (error)
				return null;
		}

		/* mark bytes as visited */
		bs.set(recordstart, buffer.position() - 1, true);
		debug("visited :: ", recordstart, " to ", buffer.position());
		int cursor = ((pagenumber - 1) * job.ps) + buffer.position();
		debug("visited :: ", (((pagenumber - 1) * job.ps) + recordstart), " to ", cursor);

		/* append header match string at the end */
		row.setLineSuffix("##header##" + header);

		// if (!tables.containsKey(idxname))
		// tables.put(idxname, new ArrayList<String[]>());
		debug(row);
		return new CarvingResult(buffer.position(), cursor, row);
	}

	/**
	 * This method can be used to read an active data record.
	 * 
	 * A regular cell has the following structure
	 * 
	 * [Cell Size / Payload][ROW ID] [Header Size][Header Columns] [Data] varint
	 * varint varint varint ...
	 * 
	 * We only need to parse the headerbytes including the serial types of each
	 * column. Afterwards we can read each data cell of the tablerow and convert
	 * into an UTF8 string.
	 * @param cellstart the start of the cell
	 * @param buffer the buffer with the data
	 * @param pagenumber_db the page number
	 * @param bs the bit set
	 * @param pagetype the page type
	 * @param maxlength the maximum length of the cell
	 * @param firstcol the buffer to be written to
	 * @param withoutROWID if there is no RowID
	 * @param filepointer the file pointer
	 * @return the row
	 * @throws IOException if an error occurs
	 * 
	 **/
	public SqliteInternalRow readRecord(int cellstart, ByteBuffer buffer, int pagenumber_db, BitSet bs, int pagetype,
			int maxlength, StringBuffer firstcol, boolean withoutROWID, int filepointer) throws IOException {

		boolean unkown = false;
		// first byte of the buffer
		buffer.position(0);

		// prepare the string for the return value
		SqliteInternalRow row = new SqliteInternalRow();

		/* is the component of this page known? */
		boolean pageInRange = pagenumber_db >= 0 && pagenumber_db < job.pages.length;
		AbstractDescriptor known = pageInRange ? job.pages[pagenumber_db] : null;

		row.setRecordType(Global.REGULAR_RECORD);
		/* For WAL and ROL files the file pointer is always greater than 0 */
		if (filepointer > 0) {
			row.setOffset((long) filepointer + cellstart);
		} else {
			/*
			 * for a regular db-file the offset is derived from the page number, since all
			 * pages are a multiple of the page size (ps).
			 */
			row.setOffset((long) (pagenumber_db - 1) * job.ps + cellstart);
		}

		if (null != known) {
			/* first, add component name if known */
			row.setTableName(known.getName());
		} else {
			/*
			 * component is not part of btree - page on free list or only present in a
			 * WAL/journal file -> need to determine name of component
			 */
			unkown = true;
		}

		if (cellstart < 0 || cellstart >= buffer.limit())
			return null;

		// length of payload as varint
		buffer.position(cellstart);
		long lpll = readUnsignedVarInt(buffer);
		if (lpll < 4 || lpll > Integer.MAX_VALUE)
			return null;
		int pll = (int) lpll;
		debug("Length of payload int : ", pll);

		long rowid = 0;

		/* Do we have a ROWID component or not? 95% of SQLite Tables have a ROWID */
		if (!withoutROWID && (unkown || known.ROWID)) {
			// read rowid as varint
			rowid = readUnsignedVarInt(buffer);
		}

		// now read the header length as varint
		int phl = (int) readUnsignedVarInt(buffer);

		/* error handling - if header length is 0 */
		if (phl == 0) {
			return null;
		}

		debug("Header Length int: ", phl, " as hex : ", Integer.toHexString(phl));

		phl -= 1;

		/*
		 * maxlength field says something about the maximum bytes we can read before in
		 * unallocated space, before we reach the cell content area (ppl + rowid header
		 * + data). Note: Sometimes the data record is already partly overwritten by a
		 * regular data record. We have only an artifact and not a complete data record.
		 * 
		 * For a regular data field startRegion the content area the value of maxlength
		 * should be INTEGER.max_value 2^32
		 */
		// System.out.println(" bufferposition :: " + buffer.position() + " headerlength
		// " + phl );
		maxlength = maxlength - phl; // - buffer.position();

		// read header bytes with serial types for each column
		// Attention: this takes most of the time during a run
		SqliteElement[] columns;

		if (phl <= 0)
			return null;

		int pp = buffer.position();
		String hh = "";
		if (job.collectInternalRows) {
		    hh = getHeaderString(phl, buffer);
		}
		buffer.position(pp);
		
		columns = getColumns(phl, buffer, firstcol);

		if (null == columns) {
			debug(" No valid header. Skip recovery.");
			return null;
		}

		int co = 0;
		try {
			if (unkown) {
				
				TableDescriptor td = matchTable(columns);

				/* this is only neccessesary, when component name is unkown */
				if (null == td)
				    row.setTableName("__UNASSIGNED");
				else {
				    row.setTableName(td.tblname);
				    if (pageInRange)
				        job.pages[pagenumber_db] = td;
				}
			}
		} catch (NullPointerException err) {
			System.err.println(err);
		}

		boolean error = false;

		int so = computePayload(pll);

		int overflow = -1;

		if (so < pll) {
			int last = buffer.position();
			debug("regular spilled payload ::", so);
			if ((buffer.position() + so - phl - 1) > (buffer.limit() - 4)) {
				return null;
			}
			try {
			    /* read overflow */
			    buffer.position(buffer.position() + so - phl - 1);
				overflow = buffer.getInt();
			} catch (Exception err) {
				return null;
			}

			if (overflow < 0)
				return null;
			debug("regular overflow::::::::: ", overflow, " ", Integer.toHexString(overflow));
			buffer.position(last);

			/*
			 * we need to increment page number by one since we start counting with zero for
			 * page 1
			 */
			byte[] extended = readOverflowIterativ(overflow - 1);

			byte[] c = BufferUtil.allocateByteBuffer(pll + job.ps);

			buffer.position(0);
			byte[] originalbuffer = BufferUtil.allocateByteBuffer(job.ps);
			for (int bb = 0; bb < job.ps; bb++) {
				originalbuffer[bb] = buffer.get(bb);
			}

			buffer.position(last);
			/* copy spilled overflow of current page into extended buffer */
			
			// original code was:
			// System.arraycopy(originalbuffer, buffer.position(), c, 0, so + 7 ); // - phl
			// I'm getting ArrayIndexOutOfBoundsException
			// So try to limit the amount to copy to the available data and target capacity
			int lenToCopy = so + 7;
			if (lenToCopy > c.length) {
			    lenToCopy = c.length;
			}
			if (lenToCopy > originalbuffer.length - buffer.position()) {
			    lenToCopy = originalbuffer.length - buffer.position();
			}
			System.arraycopy(originalbuffer, buffer.position(), c, 0, lenToCopy ); // - phl
			
			/* append the rest startRegion the overflow pages to the buffer */
			try {
				if (null != extended) {
				    // copy every byte from extended (beginning with index 0) into byte-array c, at position so-phl
                    System.arraycopy(extended, 0, c, so - phl - 1, pll - so);
				}
			} catch (ArrayIndexOutOfBoundsException err) {
				err("Error IndexOutOfBounds");
			} catch (NullPointerException err2) {
				err("Error NullPointer in ");
			}

			/* now we have the complete overflow in one byte-array */
			ByteBuffer bf = ByteBuffer.wrap(c);
			bf.position(0);

			co = 0;
			/* start reading the content of each column */
			for (SqliteElement en : columns) {
				if (en == null) {
					//lineUTF.append(";NULL");
				    //row.append(new SqliteElementData(null, job.db_encoding));
					continue;
				}

				if (!withoutROWID && co == 0 && en.length == 0) {
				    row.append(new SqliteElementData(en, rowid));
				} else if (en.length == 0) {
				    if (en.type == SerialTypes.INT0) {
				        row.append(new SqliteElementData(en, 0));
				    } else {
				        row.append(new SqliteElementData(null, job.db_encoding));
				    }
				} else {
				    int len = en.length;
				    

				    if ((bf.limit() - bf.position()) < len) {
				        info(" Bufferunderflow ", (bf.limit() - bf.position()), " is lower than", len);
				        len = bf.limit() - bf.position();
				    }

				    try {
				        if (len>0) {
				            byte[] value = BufferUtil.allocateByteBuffer(len);
	                        bf.get(value);
	                        row.append(new SqliteElementData(en, value));
				        } else {
				            row.append(new SqliteElementData(null, job.db_encoding)); 
				        }
				        
				        //lineUTF.append(write(co, en, value));

				    } catch (java.nio.BufferUnderflowException bue) {
						row.append(new SqliteElementData(null, job.db_encoding));
    					err("readRecord():: overflow java.nio.BufferUnderflowException");
    				}
				}

				co++;
			}

			// set original buffer pointer to the end of the spilled payload
			// just before the next possible record
			//buffer.position(last + so - phl - 1);

		} else {
			/*
			 * record is not spilled over different pages - no overflow, just a regular
			 * record
			 *
			 * start reading the content
			 */
			co = 0;

			/*
			 * there is a max length set - because we are in the unallocated space and may
			 * not read beyond the content area start
			 */
			for (SqliteElement en : columns) {
				if (en == null) {
					//lineUTF.append(";");
				    row.append(new SqliteElementData(null, job.db_encoding));
					continue;
				}

				if (!withoutROWID && co == 0 && en.length == 0) {
                    row.append(new SqliteElementData(en, rowid));
                } else if (en.length == 0) {
                    if (en.type == SerialTypes.INT0) {
                        row.append(new SqliteElementData(en, 0));
                    } else {
                        row.append(new SqliteElementData(null, job.db_encoding));
                    }
                } else {

    				byte[] value = null;
    				if (maxlength >= en.length)
    					value = BufferUtil.allocateByteBuffer(en.length);
    				else if (maxlength > 0)
    					value = BufferUtil.allocateByteBuffer(maxlength);
    				maxlength -= en.length;
    
    				if (null == value)
    					break;
    
    				try {
    					buffer.get(value);
    				} catch (BufferUnderflowException err) {
    					info("readRecord():: no overflow ERROR ", err);
    					// err.printStackTrace();
    					return null;
    				}
    
   				    row.append(new SqliteElementData(en, value));
                }

				co++;

				if (maxlength <= 0)
					break;
			}

		}

		/* append header match string at the end */
		if (job.collectInternalRows) {
		    row.setLineSuffix("##header##" + hh);
		}

		//lineUTF.append("\n");

		/* mark as visited */
		debug("visted ", cellstart, " to ", buffer.position());
		bs.set(cellstart, buffer.position());

		if (error) {
			err("spilles overflow page error ...");
			return null;
		}
		debug(row);
		return row;
	}

	private TableDescriptor matchTable(SqliteElement[] header) {

		for (TableDescriptor table : job.headers.values()) {
			if (table.getColumntypes().size() == header.length) {
				int idx = 0;
				boolean eq = true;

				// System.out.println("TDS component " + table.tblname + " :: " +
				// table.columntypes.toString());

				// List<String> tblcolumns = table.columntypes;

				for (SqliteElement s : header) {
					String type = table.getColumntypes().get(idx);

					// System.out.println(s.serial.name() + " <?>" + type);
					if (!s.serial.name().equals(type)) {
						eq = false;
						break;
					}
					idx++;
				}

				if (eq) {

					// System.out.println(" Match found " + table.tblname);
					return table;
				}
			}
		}

		return null;
	}
	
	/**
     * Reads the specified page as overflow.
     * 
     * Background: When the payload of a record is to large, it is spilled onto
     * overflow pages. Overflow pages form a linked list. The first four bytes of
     * each overflow page are a big-endian integer which is the page number of the
     * next page in the chain, or zero for the final page in the chain. The fifth
     * byte through the last usable byte are used to hold overflow content.
     * 
     * @param pagenumber
     * @return
     *
     */
    private byte[] readOverflowIterativ(int pagenumber) throws IOException
    {
        List<ByteBuffer> parts = new LinkedList<ByteBuffer>();
        boolean more = true;
        ByteBuffer overflowpage = null;
        int next = pagenumber;


        while(more)
        {
            info("before Read() ", next);
            /* read next overflow page into buffer */
            overflowpage = job.readPageWithNumber(next, job.ps);

            if (overflowpage != null) {
                overflowpage.position(0);
                next = overflowpage.getInt()-1;
                info(" next overflow:: ", next);
            } 
            else {
                more = false;
                break;
            }       


            /*
             * we always crab the complete overflow-page minus the first four bytes - they
             * are reserved for the (possible) next overflow page offset
             **/
            byte[] current = BufferUtil.allocateByteBuffer(job.ps - 4);
            overflowpage.position(4);
            overflowpage.get(current, 0, job.ps - 4);
            // Wrap a byte array into a buffer
            ByteBuffer part = ByteBuffer.wrap(current);
            parts.add(part);

            if (next < 0 || next > job.numberofpages) {
                // termination condition for the recursive callup's
                debug("No further overflow pages");
                /* startRegion the last overflow page - do not copy the zero bytes. */
                more = false;
            }

        }

        /* try to merge all the ByteBuffers into one array */
        if (parts == null || parts.size() == 0) {
            return ByteBuffer.allocate(0).array();
        } 
        else if (parts.size() == 1) {
            return parts.get(0).array();
        } 
        else {
            ByteBuffer fullContent = ByteBuffer.allocate(parts.stream().mapToInt(Buffer::capacity).sum());
            parts.forEach(fullContent::put);
            fullContent.flip();
            return fullContent.array();     
        }

    }

	/**
	 * Convert a base16 string into a byte array.
	 * @param s the String to be decoded
	 * @return the decoded byte array
	 */
	public static byte[] decode(String s) {
		char[] data = s.toCharArray();
		int len = data.length;
		byte[] r = BufferUtil.allocateByteBuffer(len >> 1);
		for (int i = 0, j = 0; j < len; i++) {
			int f = Character.digit(data[j++], 16) << 4;
			f = f | Character.digit(data[j++], 16);
			r[i] = (byte) (f & 0xFF);
		}
		return r;
	}

	/**
	 * This method can be used to compute the payload length for a record, which pll
	 * field is deleted or overwritten.
	 * 
	 * @param header should be the complete header without headerlength byte
	 * @return the number of bytes including header and payload
	 */
	private int computePayloadLength(String header) {
		// System.out.println("HEADER" + header);
		byte[] bcol = Auxiliary.decode(header);

		long[] columns = readVarInt(bcol);
		int pll = 0;

		pll += header.length() / 2 + 1;

		for (int i = 0; i < columns.length; i++) {
			switch ((int)columns[i]) {
			case 0: // zero length - primary key is saved in indices component
				break;
			case 1:
				pll += 1; // 8bit complement integer
				break;
			case 2: // 16bit integer
				pll += 2;
				break;
			case 3: // 24bit integer
				pll += 3;
				break;
			case 4: // 32bit integer
				pll += 4;
				break;
			case 5: // 48bit integer
				pll += 6;
				break;
			case 6: // 64bit integer
				pll += 8;
				break;
			case 7: // Big-endian floating point number
				pll += 8;
				break;
			case 8: // Integer constant 0
				break;
			case 9: // Integer constant 1
				break;
			case 10: // not used ;
				break;
			case 11:
				break;

			default:
				if (columns[i] % 2 == 0) // even
				{
					// BLOB with the length (N-12)/2
					pll += (columns[i] - 12) / 2;
				} else // odd
				{
					// String in database encoding (N-13)/2
					pll += (columns[i] - 13) / 2;
				}

			}

		}

		return pll;
	}

	/**
	 * 
	 * @param header string with the header
	 * @param charset Charset used in database
	 * @return the columns
	 */
	public static SqliteElement[] toColumns(String header, Charset charset) {
		/* hex-String representation to byte array */
		byte[] bcol = decode(header);
		return convertHeaderToSqliteElements(bcol, charset);
	}

	public String getHeaderString(int headerlength, ByteBuffer buffer) {
		byte[] header = BufferUtil.allocateByteBuffer(headerlength);

		try {
			// get header information
			buffer.get(header);

		} catch (Exception err) {
			err("ERROR ", err);
		}

		String sheader = bytesToHex(header);

		return sheader;
	}

	/**
	 * A passed ByteBuffer is converted into a byte array. Afterwards it is used to
	 * extract the column types. Exactly one element is created per column type.
	 * 
	 * @param headerlength total length of the header in bytes
	 * @param buffer       the headerbytes
	 * @param firstcol the buffer to be filled with the first column
	 * @return the column field
	 * @throws IOException if the buffer is not readable
	 */
	public SqliteElement[] getColumns(int headerlength, ByteBuffer buffer, StringBuffer firstcol) throws IOException {

		byte[] header = BufferUtil.allocateByteBuffer(headerlength);

		try {
			// get header information
			buffer.get(header);

		} catch (Exception err) {
			err("Auxiliary::ERROR ", err);
			return null;
		}

		String sheader = byteToHex(header[0]);

		if (sheader.length() > 1) {
			firstcol.insert(0, sheader);
		}
		// System.out.println("getColumns():: + Header: " + sheader);

		return convertHeaderToSqliteElements(header, job.db_encoding);
	}

	/**
	 * Converts the header bytes of a record into a field of SQLite elements.
	 * Exactly one element is created per column type.
	 * 
	 * @param header Header bytes to read from
	 * @param charset Charset to be used in decoding
	 * @return SqliteElements converted
	 */
	public static SqliteElement[] convertHeaderToSqliteElements(byte[] header, Charset charset) {
		// there are several varint values in the serialtypes header
		long[] columns = readVarInt(header);
		if (null == columns)
			return null;

		SqliteElement[] column = new SqliteElement[columns.length];

		for (int i = 0; i < columns.length; i++) {

			switch ((int)columns[i]) {
			case 0: // primary key or null value <empty> cell
				column[i] = new SqliteElement(SerialTypes.PRIMARY_KEY, StorageClasses.INT, 0, charset);
				break;
			case 1: // 8bit complement integer
				column[i] = new SqliteElement(SerialTypes.INT8, StorageClasses.INT, 1, charset);
				break;
			case 2: // 16bit integer
				column[i] = new SqliteElement(SerialTypes.INT16, StorageClasses.INT, 2, charset);
				break;
			case 3: // 24bit integer
				column[i] = new SqliteElement(SerialTypes.INT24, StorageClasses.INT, 3, charset);
				break;
			case 4: // 32bit integer
				column[i] = new SqliteElement(SerialTypes.INT32, StorageClasses.INT, 4, charset);
				break;
			case 5: // 48bit integer
				column[i] = new SqliteElement(SerialTypes.INT48, StorageClasses.INT, 6, charset);
				break;
			case 6: // 64bit integer
				column[i] = new SqliteElement(SerialTypes.INT64, StorageClasses.INT, 8, charset);
				break;
			case 7: // Big-endian floating point number
				column[i] = new SqliteElement(SerialTypes.FLOAT64, StorageClasses.FLOAT, 8, charset);
				break;
			case 8: // Integer constant 0
				column[i] = new SqliteElement(SerialTypes.INT0, StorageClasses.INT, 0, charset);
				break;
			case 9: // Integer constant 1
				column[i] = new SqliteElement(SerialTypes.INT1, StorageClasses.INT, 0, charset);
				break;
			case 10: // not used ;
				columns[i] = 0;
				break;
			case 11:
				// columns[i] = 0;
				break;
			default:
				if (columns[i] % 2 == 0) // even
				{
					// BLOB with the length (N-12)/2
				    int len = (int) (columns[i] - 12) / 2;
				    if (len >= 0) {
				        column[i] = new SqliteElement(SerialTypes.BLOB, StorageClasses.BLOB, len, charset);
				    }
				} 
				else // odd
				{
					// String in database encoding (N-13)/2
				    int len = (int) (columns[i] - 13) / 2;
				    if (len >= 0 ) {
				        column[i] = new SqliteElement(SerialTypes.STRING, StorageClasses.TEXT, len, charset);
				    }
				}

			}

		}

		return column;
	}

	/**
	 * Computes the amount of payload that spills onto overflow pages.
	 * 
	 * @param p Payload size
	 * @return
	 */
	private int computePayload(int p) {

		// let U is the usable size of a database page,
		// the total page size less the reserved space at the end of each page.
		int u = job.ps;
		// x represents the maximum amount of payload that can be stored directly
		int x = u - 35;
		// m represents the minimum amount of payload that must be stored on the btree
		// page
		// before spilling is allowed
		int m = ((u - 12) * 32 / 255) - 23;

		int k = m + ((p - m) % (u - 4));
		
		info("p ", p);
        info("k ", k);
        info("m ", m);

		// case 1: all P bytes of payload are stored directly on the btree page without
		// overflow.
		if (p <= x)
			return p;
		// case 2: first K bytes of P are stored on the btree page and the remaining P-K
		// bytes are stored on overflow pages.
		if ((p > x) && (k <= x))
			return k;
		// case 3: then the first M bytes of P are stored on the btree page and the
		// remaining P-M bytes are stored on overflow pages.
		if ((p > x) && (k > x))
			return m;

		return p;
	}

	/**
	 * This method reads a Varint value startRegion the transferred buffer. A varint
	 * has a length between 1 and 9 bytes. The MSB displays whether further bytes
	 * follow. If it is set to 1, then at least one more byte can be read.
	 * 
	 * @param buffer with varint value
	 * @return a normal integer value extracted startRegion the buffer
	 * @throws IOException if the buffer is empty
	 */
	public static long readUnsignedVarInt(ByteBuffer buffer) throws IOException {

	    byte b = buffer.get();
        long value = b & 0x7F;
        int counter = 0;
        while ((b & 0x80) != 0 && counter < 8) {
            counter ++;
            b = buffer.get();
            value <<= 7;
            if (counter == 8)
                value |= (b & 0xFF);
            else
                value |= (b & 0x7F);
            
        }
        return value;
	}

	/**
	 * Auxiliary method for reading one of a two-byte number in a data field of type
	 * short.
	 * 
	 * @param b the two bytes byte buffer
	 * @return the decoded value
	 */
	public static int TwoByteBuffertoInt(ByteBuffer b) {
	
		
		byte [] ret = new byte[4];
		ret[2] = b.get(0);
		ret[3] = b.get(1);
		
		return ByteBuffer.wrap(ret).getInt();
	}
	
	/**
     * Auxiliary method for reading one of a two-byte number in a data field of type
     * short.
     * 
     * @param b the two bytes buffer
     * @return the decoded value
     */
    public static int TwoByteBuffertoInt(byte [] b) {
    
        
        byte [] ret = new byte[4];
        ret[2] = b[0];
        ret[3] = b[1];
        
        return ByteBuffer.wrap(ret).getInt();
    }
    
	/**
	 * This method will create and assign a pattern object for matching header
	 * information from a given index entry on binary level.
	 * 
	 * @param id the index descriptor
	 */
	public static void addHeadPattern2Idx(IndexDescriptor id) {
		List<String> colnames = id.columnnames;
		List<String> coltypes = id.columntypes;

		/* create a pattern object for constrain matching of records */
		HeaderPattern pattern = new HeaderPattern();

		/* the pattern always starts with a header constraint */
		pattern.addHeaderConstraint(colnames.size() + 1, colnames.size() + 1);

		/*
		 * map the correct constraint object to a column type for all columns within the
		 * index component
		 */
		ListIterator<String> list = coltypes.listIterator();
		while (list.hasNext()) {

			switch (list.next()) {
			case "INT":
				pattern.add(new IntegerConstraint(false));
				break;

			case "TEXT":
				pattern.addStringConstraint();
				break;

			case "BLOB":
				pattern.addBLOBConstraint();
				break;

			case "REAL":

				pattern.addFloatingConstraint();
				break;

			case "NUMERIC":
				pattern.addNumericConstraint();
				break;
			}
		}

		/* assign the new matching pattern with the index descriptor object */
		id.hpattern = pattern;

		Logger.out.info("addHeaderPattern2Idx() :: PATTTERN: ", pattern);

	}

	private static byte[] HEX_DIGITS = "0123456789abcdef".getBytes();
	/**
     * Converts a byte array to Hex-String. 
     * @param bytes Bytes
     * @return String with hex representation of bytes
     */
	public static String bytesToHex(byte[] bytes) {
		byte [] hexBytes = new byte[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
		    hexBytes[i*2] = HEX_DIGITS[(bytes[i] & 0xff) >> 4];
		    hexBytes[i*2 + 1] = HEX_DIGITS[bytes[i] & 0x0f];
		}
		return new String(hexBytes);
	}

	/**
     * Converts a single byte to a Hex-String.
     * @param b byte
     * @return String with hex representation of byte
     */
	public static String byteToHex(byte b) {
		byte[] ch = new byte[1];
		ch[0] = b;
		return bytesToHex(ch);
	}
	
	/**
     * Converts the content of a ByteBuffer object 
     * into a Hex-String.
     * 
     * @param bb Bytes
     * @return String with hex representation of bytes
     */
	public static String bytesToHex(ByteBuffer bb)
	{
		int limit = bb.limit();
		StringBuilder sb = new StringBuilder(limit * 2);
		
		bb.position(0);
		
		while (bb.position() < limit)
		{
		
			int intVal = bb.get() & 0xFF;
			if (intVal < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(intVal));
		}
		
		return sb.toString();
	}
	
	/**
     * Converts specified range of the specified array into a Hex-String. 
     * The initial index of the range (from) must lie between zero and 
     * original.length, inclusive. 
     * 
     * @param bytes Bytes
     * @param fromidx start index
     * @param toidx end index
     * @return String with hex representation of bytes
     */
	public static String bytesToHex(byte[] bytes, int fromidx, int toidx) {
	    byte [] hexBytes = new byte[(toidx-fromidx) * 2];
        for (int i = fromidx; i < toidx; i++) {
            hexBytes[(i-fromidx)*2] = HEX_DIGITS[(bytes[i] & 0xff) >> 4];
            hexBytes[(i-fromidx)*2 + 1] = HEX_DIGITS[bytes[i] & 0x0f];
        }
        return new String(hexBytes);
	}

	
	/**
     * Read a variable length integer from the supplied ByteBuffer
     * @param values in buffer to read from
     * @return the int value
     */
    public static long[] readVarInt(byte[] values){

        ByteBuffer in = ByteBuffer.wrap(values);
        LinkedList<Long> res = new LinkedList<Long>();


        while (in.position() < in.limit()) {
            byte b = in.get();            
            long value = b & 0x7F;
            int counter = 0;
            while ((b & 0x80) != 0 && counter < 8) {
                counter ++;
                if (in.position() >= in.limit()) {
                    return null;
                }
                b = in.get();
                value <<= 7;
                if (counter == 8) {
                    value |= (b & 0xFF); 
                } else {
                    value |= (b & 0x7F);
                }
            }

            res.add(value);
        } 

		return res.stream().mapToLong(i -> i).toArray();        
    }
	
	public static String getSerial(SqliteElement[] columns) {
		String serial = "";

		for (SqliteElement e : columns)
			serial += e.serial;
		return serial;
	}

	public static String getTableFingerPrint(SqliteElement[] columns) {
		String fp = "";

		for (SqliteElement e : columns)
			fp += e.type;
		return fp;
	}

	public static void printStackTrace() {
		Logger.out.info("Printing stack trace:");

		StackTraceElement[] elements = Thread.currentThread().getStackTrace();
		for (int i = 1; i < elements.length; i++) {
			StackTraceElement s = elements[i];
			Logger.out.info("\tat ", s.getClassName(), ".", s.getMethodName(), "(", s.getFileName(), ":",
					s.getLineNumber(), ")");
		}
	}
	
	/**
	 * This method can be used to compute the payload length for a record, which pll
	 * field is deleted or overwritten.
	 * 
	 * @param header should be the complete header without headerlength byte
	 * @return the number of bytes including header and payload
	 */
	public static int computePayloadLengthS(String header) {
	    Logger.out.info("HEADER", header);
		byte[] bcol = Auxiliary.decode(header);
		long[] columns = Auxiliary.readVarInt(bcol);
		int pll = 0;

	
		pll += header.length() / 2 + 1;

		for (int i = 0; i < columns.length; i++) {
			switch ((int) columns[i]) {
			case 0: // zero length - primary key is saved in indices component
				break;
			case 1:
				pll += 1; // 8bit complement integer
				break;
			case 2: // 16bit integer
				pll += 2;
				break;
			case 3: // 24bit integer
				pll += 3;
				break;
			case 4: // 32bit integer
				pll += 4;
				break;
			case 5: // 48bit integer
				pll += 6;
				break;
			case 6: // 64bit integer
				pll += 8;
				break;
			case 7: // Big-endian floating point number
				pll += 8;
				break;
			case 8: // Integer constant 0
				break;
			case 9: // Integer constant 1
				break;
			case 10: // not used ;
				break;
			case 11:
				break;

			default:
				if (columns[i] % 2 == 0) // even
				{
					// BLOB with the length (N-12)/2
					pll += (columns[i] - 12) / 2;
				} else // odd
				{
					// String in database encoding (N-13)/2
					pll += (columns[i] - 13) / 2;
				}

			}

		}

		return pll;
	}
	
	/**
	 * Computes the amount of payload that spills onto overflow pages.
	 * 
	 * @param p Payload size
	 * @param ps Page size
	 * @return the amount of bytes that spills onto overflow pages
	 */
	public static int computePayloadS(int p, int ps) {

		// let U is the usable size of a database page,
		// the total page size less the reserved space at the end of each page.
		int u = ps;
		// x represents the maximum amount of payload that can be stored directly
		int x = u - 35;
		// m represents the minimum amount of payload that must be stored on the btree
		// page
		// before spilling is allowed
		int m = ((u - 12) * 32 / 255) - 23;

		int k = m + ((p - m) % (u - 4));

		// case 1: all P bytes of payload are stored directly on the btree page without
		// overflow.
		if (p <= x)
			return p;
		// case 2: first K bytes of P are stored on the btree page and the remaining P-K
		// bytes are stored on overflow pages.
		if ((p > x) && (k <= x))
			return k;
		// case 3: then the first M bytes of P are stored on the btree page and the
		// remaining P-M bytes are stored on overflow pages.
		if ((p > x) && (k > x))
			return m;

		return p;
	}
    
    private static final int[] FALSE_INT_ARRAY_RESP = new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE};
    
    private int[] validateHeaderBytes(byte[] headerBytes, int start) {
        int size = headerBytes[start] - 1;
        if (size < headerBytes.length - start) {
            byte[] headerCols = new byte[size];
            System.arraycopy(headerBytes, start + 1, headerCols, 0, size);
            SqliteElement[] cols = convertHeaderToSqliteElements(headerCols, job.db_encoding);
            if (cols != null) {
                return new int[] {cols.length, size};
            }
        }
        return FALSE_INT_ARRAY_RESP;
    }
       
    /**
     * Verify if the bytes form a valid header of a record for the master table
     * 
     * @param headerBytes the header bytes
     * @param start position of first byte to be read in buffer
     * @param end end of the header bytes
     * @return int[] first element - start of the header, second element - size of header
     */
    public int[] validateIntactMasterTableHeader(byte[] headerBytes, int start, int end) {
        for (int i = 0; i < 6; i++) {
            int firstByte = headerBytes[i+start];
            if (firstByte >= 6 && firstByte <= 9) {
                if (start + i + firstByte != end)
                    continue;
                int [] valData = validateHeaderBytes(headerBytes, i+start);
                int numCols = valData[0];
                int sizeHeader = valData[1];
                if (numCols == 5) {
                    return new int[] {i, sizeHeader + 1};
                }
            }
        }
        return FALSE_INT_ARRAY_RESP;
    }
    
    /**
     * Verify if the bytes form a valid header of a record for the master table
     * 
     * @param headerBytes the header bytes
     * @param end end of the header bytes
     * @return int[] first element - start of the header, second element - size of header
     */
    public int[] validateIntactMasterTableHeader(byte[] headerBytes, int end) {
        return validateIntactMasterTableHeader(headerBytes, 0, end);
    }
    
    /**
     * Verify if the bytes form a valid header of a partially overwritten record for the master table
     * 
     * @param headerBytes the header bytes
     * @param end end of the header bytes
     * @return int[] first element - start of the header, second element - size of header
     */
    public int[] validateOverwrittenMasterTableHeader(byte[] headerBytes, int end) {
        byte [] headerBytesExtended = new byte[headerBytes.length + 1];
        System.arraycopy(headerBytes, 0, headerBytesExtended, 1, headerBytes.length);
        for (int i = 0; i < 6; i++) {
            for (int size = 6; size <= 9; size ++) {
                headerBytesExtended[i] = (byte) (size & 0xFF);
                int [] valData = validateIntactMasterTableHeader(headerBytesExtended, i, end + 1);
                if (valData[0] >= 0) {
                    return new int[] { i + valData[0] - 1, valData[1] };
                }
            }
        }
        return FALSE_INT_ARRAY_RESP;
    }
	
}
