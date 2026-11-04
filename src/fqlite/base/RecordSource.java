package fqlite.base;

/**
 * Identifies the file a recovered record was read from.
 */
public enum RecordSource {
    /** The main database file. */
    DATABASE,
    /** A frame of the write ahead log (-wal file). */
    WAL,
    /** A page of the rollback journal (-journal file). */
    ROLLBACK_JOURNAL
}
