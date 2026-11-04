package fqlite.base;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;

import fqlite.types.SerialTypes;
import fqlite.util.Auxiliary;
import fqlite.util.BufferUtil;
import fqlite.util.CarvingResult;
/**
 * This class provides different access methods to read the different cells records from a SQLite database.
 * 
 * We distinguish in between regular records, overflow and deleted records.
 * 
 * A separate access method is provided for each type.
 * 
 * @author pawlaszc
 *
 */
public class PageReader extends Base {

	private Job job;

	/**
	 * Constructor. To return values to the calling job environment, an object reference of
	 * job object is required.
	 * 
	 * @param job the job object
	 */
	public PageReader(Job job) {
		this.job = job;
	}

	/**
	 * An important step in data recovery is the analysis of the database schema.
	 * This method allows to read in the schema description into a ByteBuffer.
	 * 
	 * @param job the job object
	 * @param start the start position
	 * @param buffer the buffer to write the schema into
	 * @param header the header
	 * @throws IOException if an I/O error occurs
	 */
	public void readMasterTableRecord(Job job, int start, ByteBuffer buffer, String header) throws IOException {
		
		SqliteElement[] columns;

		buffer.position(start);
		
		columns = Auxiliary.toColumns(header, job.db_encoding);

		if (null == columns)
			return;
		
		// use the header information to reconstruct 
		int pll = Auxiliary.computePayloadLengthS(header);

		int so = Auxiliary.computePayloadS(pll,job.ps);

		int overflow = -1;

		if (so < pll) {
			int phl = header.length() / 2;

			int last = buffer.position();
			debug(" spilled payload ::", so);
			debug(" pll payload ::", pll);
			buffer.position(buffer.position() + so - phl - 1);

			overflow = buffer.getInt();
			debug(" overflow::::::::: ", overflow, " ", Integer.toHexString(overflow));
			buffer.position(last);
		
				/*
				 * we need to increment page number by one since we start counting with zero for
				 * page 1
				 */
				byte[] extended = readOverflow(overflow -1);

				byte[] c = BufferUtil.allocateByteBuffer(pll + job.ps);

				buffer.position(0);
				
				/* method array() cannot be called, since we backed an array*/
				byte [] originalbuffer = BufferUtil.allocateByteBuffer(job.ps);
				for (int bb = 0; bb < job.ps; bb++)
				{
				   originalbuffer[bb] = buffer.get(bb);	
				}	
				
				buffer.position(last);
				
				/* copy spilled overflow of current page into extended buffer */
				System.arraycopy(originalbuffer, buffer.position(), c, 0, so - phl);
				/* append the rest startRegion the overflow pages to the buffer */
				System.arraycopy(extended, 0, c, so - phl -1, extended.length); //- so);
			  	ByteBuffer bf = ByteBuffer.wrap(c);

			  	buffer = bf;
				
			
				// set original buffer pointer to the end of the spilled payload
				// just before the next possible record
				buffer.position(0);
		} 

		int con = 0;
		
		String tablename = null;
		int rootpage = -1;
		String statement = null;
		
		 /* start reading the content */
		for (SqliteElement en : columns) {
		
			
			if (en == null) {
				continue;
			}
			
			byte[] value = null;
			
			value = BufferUtil.allocateByteBuffer(en.length);
				
			buffer.get(value);
		
			/* column 3 ? -> tbl_name TEXT */
			if (con == 3)
			{	
				tablename = en.toString(value);
			}
				
			/* column 4 ?  -> root page Integer */
			if (con == 4)
			{	
				rootpage = SqliteElement.decodeInt8(value[0]);
			}
		
			/* read sql statement */
			
			if (con == 5)
			{	
				statement = en.toString(value);				
			}
		
			
			
		    con++;
			
		}	
		//finally, we have all information in place to parse the CREATE statement
		job.schemaParser.parse(job,tablename, rootpage, statement);

	}

	/**
	 * This method is used to extract a previously deleted record startRegion a page. 
	 * 
	 * @param job the job object
	 * @param start  the exact position (offset relative to the page start).
	 * @param buffer a ByteBuffer with the data page to analyze.
	 * @param header the record header bytes including header length and serial types.
	 * @param bs	a data structure that is used to record which areas have already been searched 
	 * @param pagenumber  the number of the page we going to analyze
	 * @return the CarvingResult
	 * @throws IOException  if something went wrong during read-up. 
	 */
	public CarvingResult readDeletedRecord(Job job, int start, ByteBuffer buffer, String header, BitSet bs,
			int pagenumber) throws IOException {

		SqliteElement[] columns;

		buffer.position(start);

		int recordstart = start - (header.length() / 2) - 2;

		columns = Auxiliary.toColumns(header, job.db_encoding);

		if (null == columns)
			return null;

		SqliteInternalRow row = new SqliteInternalRow();
		// String[] row = new String[columns.length]; // set to maximum page size
		//int co = 0;
		String fp = null;
		try {
			fp = Auxiliary.getTableFingerPrint(columns);

		} catch (NullPointerException err) {
			// System.err.println(err);
		}
		if (null == fp)
			fp = "unkown";
		// String idxname = Signatures.getTable(fp);

		boolean error = false;

		row.setOffset((long) (pagenumber - 1) * job.ps + buffer.position());

		/* use the header information to reconstruct */
		int pll = Auxiliary.computePayloadLengthS(header);

		int so = Auxiliary.computePayloadS(pll,job.ps);

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
			if (overflow < job.numberofpages) {

				/*
				 * we need to increment page number by one since we start counting with zero for
				 * page 1
				 */
				byte[] extended = readOverflow(overflow - 1);

				byte[] c = BufferUtil.allocateByteBuffer(pll + job.ps);

				buffer.position(0);
				byte [] originalbuffer = BufferUtil.allocateByteBuffer(job.ps);
				for (int bb = 0; bb < job.ps; bb++)
				{
				   originalbuffer[bb] = buffer.get(bb);	
				}	
				
				buffer.position(last);
				
				/* copy spilled overflow of current page into extended buffer */
				System.arraycopy(originalbuffer, buffer.position(), c, 0, so - phl);
				/* append the rest startRegion the overflow pages to the buffer */
				System.arraycopy(extended, 0, c, so - phl, extended.length - so);
				bf = ByteBuffer.wrap(c);

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

				if (en.length == 0) {
                    if (en.type == SerialTypes.INT0) {
                        row.append(new SqliteElementData(en, 0));
                    } else {
                        row.append(new SqliteElementData(null, job.db_encoding));
                    }
                } else {
                    int len = en.length;
                    
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
		bs.set(recordstart, buffer.position()-1, true);
		debug("Besucht :: ", recordstart, " to ", buffer.position());
		int cursor = ((pagenumber - 1) * job.ps) + buffer.position();
		debug("Besucht :: ", (((pagenumber - 1) * job.ps) + recordstart), " bis ", cursor);
        
		
		// if (!tables.containsKey(idxname))
		// tables.put(idxname, new ArrayList<String[]>());
		debug(row);
		return new CarvingResult(buffer.position(),cursor, row);
	}

	/**
	 * Reads the specified page as overflow. 
	 * 
	 * Background:
	 * When the payload of a record is to large, it is spilled onto overflow pages.
	 * Overflow pages form a linked list. The first four bytes of each overflow page are a 
	 * big-endian integer which is the page number of the next page in the chain, or zero for 
	 * the final page in the chain. The fifth byte through the last usable byte are used to 
	 * hold overflow content.
     *
     *
	 * @param pagenumber
	 * @return all bytes that belong to the payload 
	 * @throws IOException
	 */
	private byte[] readOverflow(int pagenumber) throws IOException {
		byte[] part = null;

		/* read the next overflow page startRegion file */
		ByteBuffer overflowpage = job.readPageWithNumber(pagenumber, job.ps);

		overflowpage.position(0);
		int overflow = overflowpage.getInt();
		debug(" overflow:: " + overflow);

		if (overflow == 0) {
			// termination condition for the recursive callup's
			debug("No further overflow pages");
			/* startRegion the last overflow page - do not copy the zero bytes. */
		} else {
			/* recursively call next overflow page in the chain */
			part = readOverflow(overflow);
		}

		/*
		 * we always crab the complete overflow-page minus the first four bytes - they
		 * are reserved for the (possible) next overflow page offset
		 **/
		byte[] current = BufferUtil.allocateByteBuffer(job.ps - 4);
		//System.out.println("current ::" + current.length);
		//System.out.println("bytes:: " + (job.ps -4));
		//System.out.println("overflowpage :: " + overflowpage.limit());
		
		overflowpage.position(4);
		overflowpage.get(current, 0, job.ps - 4);
		// overflowpage.get(current, 0, job.ps-4);

		/* Do we have a predecessor page? */
		if (null != part) {
			/* merge the overflow pages together to one byte-array */
			byte[] of = BufferUtil.allocateByteBuffer(current.length + part.length);
			System.arraycopy(current, 0, of, 0, current.length);
			System.arraycopy(part, 0, of, current.length, part.length);
			return of;
		}

		/* we have the last overflow page - no predecessor pages */
		return current;
	}

	/**
	 * A passed ByteBuffer is converted into a byte array. Afterwards it is used
	 * to extract the column types. Exactly one element is created per column type. 
	 * 
	 * @param headerlength total length of the header in bytes
	 * @param buffer the headerbytes
	 * @return the column field
	 * @throws IOException if an error ocurred
	 */
	public SqliteElement[] getColumns(int headerlength, ByteBuffer buffer) throws IOException {

		byte[] header = BufferUtil.allocateByteBuffer(headerlength);

		try
		{
			// get header information
			buffer.get(header);
		}
		catch(Exception err)
		{
			err("ERROR " + err.toString());
		}
		
		debug("Header: ", header);
		
		return Auxiliary.convertHeaderToSqliteElements(header, job.db_encoding);
	}
}
