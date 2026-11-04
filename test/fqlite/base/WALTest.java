package fqlite.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WALTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final int COMMITS = 5;
    private static final int ROWS_PER_COMMIT = 30;

    private static void insertRows(java.sql.Connection c) throws java.sql.SQLException {
        for (int k = 0; k < COMMITS; k++) {
            c.setAutoCommit(false);
            for (int i = 0; i < ROWS_PER_COMMIT; i++) {
                TestSupport.exec(c, "INSERT INTO t(v) VALUES('walrow-" + k + "-" + i + "-" + TestSupport.repeat('x', 100) + "')");
            }
            c.commit();
            c.setAutoCommit(true);
        }
    }

    private static Set<String> expectedValues() {
        Set<String> result = new java.util.HashSet<>();
        for (int k = 0; k < COMMITS; k++) {
            for (int i = 0; i < ROWS_PER_COMMIT; i++) {
                result.add("walrow-" + k + "-" + i + "-" + TestSupport.repeat('x', 100));
            }
        }
        return result;
    }

    private static Set<String> walValues(Job job) {
        return job.getRowsForTable("t").stream().filter(r -> r.getSource() == RecordSource.WAL)
                .map(r -> r.getTextValue("v")).filter(v -> v != null && v.startsWith("walrow-"))
                .collect(Collectors.toSet());
    }

    /** Every frame of the WAL must be analyzed (not only the first one). */
    @Test
    public void allFramesAreAnalyzed() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path db = TestSupport.createWalDb(root, root.resolve("out"), "w.db",
                c -> TestSupport.exec(c, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)"),
                WALTest::insertRows);
        Job job = TestSupport.run(db, Path.of(db + "-wal"));
        assertEquals(expectedValues(), walValues(job));
    }

    /** The schema is found in the WAL when the main database file has none yet. */
    @Test
    public void schemaOnlyInWal() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path db = TestSupport.createWalDb(root, root.resolve("out"), "w.db", null, c -> {
            TestSupport.exec(c, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)");
            insertRows(c);
        });
        Job job = TestSupport.run(db, Path.of(db + "-wal"));
        assertTrue(job.headers.containsKey("t"));
        assertEquals(expectedValues(), walValues(job));
    }

    /** readWAL without an explicit path uses the default "-wal" file. */
    @Test
    public void defaultWalPath() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path db = TestSupport.createWalDb(root, root.resolve("out"), "w.db",
                c -> TestSupport.exec(c, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)"),
                WALTest::insertRows);
        Job job = new Job();
        job.readWAL = true;
        assertEquals(0, job.run(db.toString()));
        assertEquals(expectedValues(), walValues(job));
    }

    /** Records deleted in the WAL can still be found in older frames. */
    @Test
    public void deletedRowsInOlderFrames() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path db = TestSupport.createWalDb(root, root.resolve("out"), "w.db",
                c -> TestSupport.exec(c, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)"), c -> {
                    insertRows(c);
                    TestSupport.exec(c, "DELETE FROM t");
                });
        Job job = TestSupport.run(db, Path.of(db + "-wal"));
        assertTrue(walValues(job).containsAll(expectedValues()));
        for (SqliteRow r : job.getRowsForTable("t")) {
            if (r.getSource() == RecordSource.WAL) {
                assertTrue(r.getOffset() > 32);
            }
        }
    }

    /** Without readWAL nothing from the WAL is reported. */
    @Test
    public void walIgnoredWhenDisabled() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path db = TestSupport.createWalDb(root, root.resolve("out"), "w.db",
                c -> TestSupport.exec(c, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)"),
                WALTest::insertRows);
        Job job = TestSupport.run(db);
        assertEquals(0, walValues(job).size());
    }
}
