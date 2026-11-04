package fqlite.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Corrupted (or manipulated) databases must not crash or hang the recovery.
 * Runs single threaded and multi threaded.
 */
@RunWith(Parameterized.class)
public class CorruptedDatabaseTest {

    private static final int PS = 1024;

    @Parameters(name = "threads={0}")
    public static Collection<Object[]> threads() {
        return Arrays.asList(new Object[][] { { 0 }, { 4 } });
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final int threads;
    private int savedThreads;

    public CorruptedDatabaseTest(int threads) {
        this.threads = threads;
    }

    @Before
    public void setThreads() {
        savedThreads = Global.numberofThreads;
        Global.numberofThreads = threads;
    }

    @After
    public void restoreThreads() {
        Global.numberofThreads = savedThreads;
    }

    private static String value(int i) {
        return "value-" + i + "-" + TestSupport.repeat('z', 300);
    }

    /** Table t with 6 rows of ~300 bytes: root page 2 is an interior page with 2 leaf pages. */
    private Path twoLeafDb(boolean deleteAll) throws Exception {
        return TestSupport.createDb(tmp.getRoot().toPath(), "two.db", c -> {
            TestSupport.exec(c, "PRAGMA page_size=" + PS, "PRAGMA secure_delete=OFF", "PRAGMA auto_vacuum=NONE",
                    "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)");
            for (int i = 0; i < 6; i++) {
                TestSupport.exec(c, "INSERT INTO t(v) VALUES('" + value(i) + "')");
            }
            if (deleteAll) {
                TestSupport.exec(c, "DELETE FROM t");
            }
        });
    }

    private static int readInt(Path db, long pos) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(db.toFile(), "r")) {
            f.seek(pos);
            return f.readInt();
        }
    }

    private static void writeAt(Path db, long pos, int value, boolean asShort) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(db.toFile(), "rw")) {
            f.seek(pos);
            if (asShort) {
                f.writeShort(value);
            } else {
                f.writeInt(value);
            }
        }
    }

    private static long recoveredValues(Job job) {
        return job.getRowsForTable("t").stream().map(r -> r.getTextValue("v"))
                .filter(v -> v != null && v.startsWith("value-")).distinct().count();
    }

    /** The leaf pages of a freelist trunk start at offset 8 of the trunk page. */
    @Test
    public void freelistLeafPagesAreAnalyzed() throws Exception {
        Path db = twoLeafDb(true);
        int trunk = readInt(db, 32);
        assertTrue("test setup: freelist expected", trunk > 0);
        assertEquals("test setup: one leaf page on the trunk", 1, readInt(db, (long) (trunk - 1) * PS + 4));
        Job job = TestSupport.run(db);
        assertTrue("rows of the freelist leaf page must be recovered", recoveredValues(job) >= 2);
        assertTrue(job.getRowsForTable("t").stream().allMatch(SqliteRow::isDeletedRow));
    }

    /** An interior page which points to itself must not lead to an endless recursion. */
    @Test(timeout = 30000)
    public void cyclicBTree() throws Exception {
        Path db = twoLeafDb(false);
        assertEquals("test setup: interior root page", 0x05, readInt(db, PS) >>> 24);
        /* right child pointer of the interior root page 2 -> page 2 */
        writeAt(db, PS + 8, 2, false);
        Job job = TestSupport.run(db);
        /* the left child is still found */
        assertTrue(recoveredValues(job) >= 1);
    }

    /** A leaf page with an invalid number of cells. */
    @Test(timeout = 30000)
    public void invalidCellCount() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "cells.db", c -> {
            TestSupport.exec(c, "PRAGMA page_size=" + PS, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)",
                    "CREATE TABLE u(id INTEGER PRIMARY KEY, v TEXT)");
            TestSupport.exec(c, "INSERT INTO t(v) VALUES('" + value(1) + "')");
            TestSupport.exec(c, "INSERT INTO u(v) VALUES('" + value(2) + "')");
        });
        /* page 2 is the root (leaf) page of t */
        writeAt(db, PS + 3, 0xFFFF, true);
        Job job = TestSupport.run(db);
        assertEquals(value(2), job.getRowsForTable("u").get(0).getTextValue("v"));
    }

    /** A freelist trunk page pointing to itself with an invalid number of entries. */
    @Test(timeout = 30000)
    public void cyclicFreelistWithInvalidEntries() throws Exception {
        Path db = twoLeafDb(true);
        int trunk = readInt(db, 32);
        long pos = (long) (trunk - 1) * PS;
        writeAt(db, pos, trunk, false);
        writeAt(db, pos + 4, 100000, false);
        Job job = TestSupport.run(db);
        assertTrue(job.headers.containsKey("t"));
    }
}
