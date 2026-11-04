package fqlite.util;


import java.io.IOException;
import java.util.Arrays;

/**
 * An efficient byte buffer searching class based on the Knuth-Morris-Pratt algorithm.
 * For more on the algorithm works see: 
 * http://www.inf.fh-flensburg.de/lang/algorithmen/pattern/kmpen.htm.
 */
public class ByteSeqSearcher{


	    private byte[] pattern_;
	    private int[] borders_;

	    // An upper bound on pattern length for searching. Results are undefined for longer patterns.
	    public static final int MAX_PATTERN_LENGTH = 2048;

	    public ByteSeqSearcher(byte[] pattern)
	    {
	        setPattern(pattern);
	    }

	    /**
	     * Sets a new pattern for this.
	     *
	     * @param pattern the pattern we will look for in future calls to search(...)
	     */
	    /**
	     * @return the length of the pattern searched for
	     */
	    public int patternLength()
	    {
	        return pattern_.length;
	    }

	    public void setPattern(byte[] pattern)
	    {
	        pattern_ = Arrays.copyOf(pattern, pattern.length);
	        borders_ = new int[pattern_.length + 1];
	        preProcess();
	    }
	
	    /**
	     * Searches for the next occurrence of the pattern in the buffer
	     * starting startRegion the given position
		 * @param file the FileReader
		 * @param start the start position
		 * @return the position of the next occurrence of the pattern or -1 if not found
	     * @throws IOException if an I/O error occurs
	     */
	     public long indexOf(LongPositionByteBuffer file, long start) throws IOException
	     {
	         file.position(start);
	         return indexOf(file);
	     }

	    
	    /**
	     * Searches for the next occurrence of the pattern in the buffer, starting startRegion the current buffer position. Note
	     * that the position of the buffer is changed. If a match is found, the buffer points to the end of the match -- i.e. the
	     * byte AFTER the pattern. Else, the buffer is entirely consumed. The latter is because Inputbuffer semantics make it difficult to have
	     * another reasonable default, i.e. leave the buffer unchanged.
	     *
		 * @param file the fileReader
	     * @return bytes consumed if found, -1 otherwise.
		 * @throws IOException if an I/O error occurs
	     */
	    public long indexOf(LongPositionByteBuffer file) throws IOException
	    {
	        int j = 0;
	        long pos = file.position();
	        long size = file.size();
	        if (pos >= size || pattern_.length == 0)
	        {
	            return -1;
	        }

	        /* read the input in blocks - reading byte by byte is very slow for files */
	        byte[] chunk = new byte[(int) Math.min(CHUNK_SIZE, size - pos)];

	        while (pos < size)
	        {
	            int len = (int) Math.min(chunk.length, size - pos);
	            file.position(pos);
	            file.get(chunk, 0, len);

	            for (int k = 0; k < len; k++)
	            {
	                byte b = chunk[k];
	                while (j >= 0 && b != pattern_[j])
	                {
	                    j = borders_[j];
	                }
	                // Move to the next character in the pattern.
	                ++j;

	                // If we've matched up to the full pattern length, we found it. Return
	                // the position immediately following the pattern match (the input is
	                // positioned there too).
	                if (j == pattern_.length)
	                {
	                    long end = pos + k + 1;
	                    file.position(end);
	                    return end;
	                }
	            }
	            pos += len;
	        }

	        // No dice, Note that the buffer is now completely consumed.
	        return -1;
	    }

	    private static final int CHUNK_SIZE = 65536;

	    /**
	     * Builds up a component of longest "borders" for each prefix of the pattern to find. 
	     * This component is stored internally and aids in implementation of the Knuth-Moore-Pratt 
	     * string search.
	     * <p>
	     * For more information, see: http://www.inf.fh-flensburg.de/lang/algorithmen/pattern/kmpen.htm.
	     * 
	     */
	    private void preProcess()
	    {
	        int i = 0;
	        int j = -1;
	        borders_[i] = j;
	        while (i < pattern_.length)
	        {
	            while (j >= 0 && pattern_[i] != pattern_[j])
	            {
	                j = borders_[j];
	            }
	            borders_[++i] = ++j;
	        }
	    }
	    
	  
}
