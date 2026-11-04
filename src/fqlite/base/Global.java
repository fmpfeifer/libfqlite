package fqlite.base;

public class Global {

    public static final String REGULAR_RECORD = " "; // U2713 - regular
    public static final String DELETED_RECORD_IN_PAGE = "D"; // U2718 - deleted
    public static final String FREELIST_ENTRY = "F"; // U267D - freelist
    public static final String STATUS_CLOMUN = "S"; // U291D U2691
    public static final String UNALLOCATED_SPACE = "U"; // U2318 - unallocated space
    /* record rebuilt from a deleted index entry: only the indexed columns and the rowid are known */
    public static final String INDEX_ENTRY = "I";
    public static final String FQLITE_VERSION = "1.57.05";
    public static final String FQLITE_RELEASEDATE = "04/01/2022";
    public static final int CARVING_ERROR = -1;
    public static boolean CONVERT_DATETIME = true; // Weather to convert datetime or not
    public static int LOGLEVEL = Base.ERROR;
    public static int numberofThreads = Runtime.getRuntime().availableProcessors();
    public static final int MAX_BUFFER_SIZE = 16 * 65536;
}
