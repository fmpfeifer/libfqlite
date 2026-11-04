package fqlite.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Recovery strategies beyond the cell pointer array and the carving of free space.
 */
public class RecoveryStrategiesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Pattern MARKER = Pattern.compile("msg-([0-9]+)-");

    /** Ids of the records with a "msg-<id>-" marker, optionally only deleted ones. */
    static Set<Integer> markers(Job job, String table, boolean onlyDeleted) {
        Set<Integer> ids = new HashSet<>();
        for (SqliteRow r : job.getRowsForTable(table)) {
            if (onlyDeleted && !r.isDeletedRow()) {
                continue;
            }
            Matcher m = MARKER.matcher(r.toString());
            if (m.find()) {
                ids.add(Integer.parseInt(m.group(1)));
            }
        }
        return ids;
    }

    static String body(int i, int len) {
        return "msg-" + i + "- " + TestSupport.repeat((char) ('a' + i % 26), len);
    }

    /** Ids of the markers still present in the raw bytes of the files. */
    static Set<Integer> markersInFiles(Path... files) throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (Path f : files) {
            Matcher m = MARKER.matcher(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1));
            while (m.find()) {
                ids.add(Integer.parseInt(m.group(1)));
            }
        }
        return ids;
    }

    /** Freelist trunk pages hold the freelist only in their first bytes, the rest is former content. */
    @Test
    public void freelistTrunkPages() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "trunk.db", c -> {
            TestSupport.exec(c, "PRAGMA secure_delete=OFF", "CREATE TABLE msgs(id INTEGER PRIMARY KEY, ts INTEGER, body TEXT)");
            c.setAutoCommit(false);
            for (int i = 1; i <= 3000; i++) {
                TestSupport.exec(c, "INSERT INTO msgs VALUES(" + i + ", " + i + ", '" + body(i, 10 + (i * 31) % 290) + "')");
            }
            c.commit();
            c.setAutoCommit(true);
            TestSupport.exec(c, "DELETE FROM msgs WHERE id BETWEEN 1000 AND 2000");
        });
        Set<Integer> deleted = new HashSet<>();
        for (int i = 1000; i <= 2000; i++) {
            deleted.add(i);
        }
        Set<Integer> recoverable = markersInFiles(db);
        recoverable.retainAll(deleted);
        Job job = TestSupport.run(db);
        Set<Integer> recovered = markers(job, "msgs", true);
        recoverable.removeAll(recovered);
        assertTrue("not recovered: " + recoverable, recoverable.isEmpty());
    }

    /**
     * WITHOUT ROWID tables have no rowid in their cells: the freeblock header overwrites the
     * header size and the first serial types of a deleted record. The records are rebuilt
     * from the bounds of the freeblock and the schema.
     */
    @Test
    public void freeblocksOfWithoutRowidTable() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "wr.db", c -> {
            TestSupport.exec(c, "PRAGMA secure_delete=OFF",
                    "CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT, n INTEGER) WITHOUT ROWID");
            c.setAutoCommit(false);
            for (int i = 1; i <= 3000; i++) {
                TestSupport.exec(c, "INSERT INTO kv VALUES('" + String.format("key-%06d", i) + "', '"
                        + body(i, 5 + (i * 37) % 200) + "', " + i + ")");
            }
            c.commit();
            c.setAutoCommit(true);
            TestSupport.exec(c, "DELETE FROM kv WHERE n % 4 = 0");
        });
        Job job = TestSupport.run(db);
        Set<Long> correct = new HashSet<>();
        for (SqliteRow r : job.getRowsForTable("kv")) {
            String k = r.getTextValue("k");
            String v = r.getTextValue("v");
            long n = r.getIntValue("n");
            if (r.isDeletedRow() && n % 4 == 0 && String.format("key-%06d", n).equals(k) && v != null
                    && v.startsWith("msg-" + n + "- ")) {
                correct.add(n);
            }
        }
        assertTrue("correctly rebuilt: " + correct.size(), correct.size() >= 700);
        assertEquals(2250, TestSupport.countRegular(job, "kv"));
    }

    /**
     * secure_delete=FAST cleans the deleted records of the table, but entries of an index
     * survive: the records are reported partially (indexed columns and rowid).
     */
    @Test
    public void deletedRecordsFromIndexEntries() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "idx.db", c -> {
            TestSupport.exec(c, "CREATE TABLE msgs(id INTEGER PRIMARY KEY, ts INTEGER, sender TEXT, body TEXT)",
                    "CREATE INDEX idx_sender ON msgs(sender)");
            c.setAutoCommit(false);
            for (int i = 1; i <= 3000; i++) {
                TestSupport.exec(c, "INSERT INTO msgs VALUES(" + i + ", " + i + ", 'sender" + (i % 50) + "', '"
                        + body(i, 10 + i % 290) + "')");
            }
            c.commit();
            c.setAutoCommit(true);
            TestSupport.exec(c, "PRAGMA secure_delete=FAST", "DELETE FROM msgs WHERE id % 4 = 0");
        });
        Job job = TestSupport.run(db);
        assertEquals(2250, TestSupport.countRegular(job, "msgs"));
        int fromIndex = 0;
        for (SqliteRow r : job.getRowsForTable("msgs")) {
            /* records rebuilt from index entries: rowid and indexed column only */
            if (r.isDeletedRow() && r.getTextValue("body") == null && r.getTextValue("sender") != null
                    && r.getIntValue("id") != Long.MIN_VALUE) {
                long id = r.getIntValue("id");
                assertEquals("deleted record " + id, 0, id % 4);
                assertEquals("sender" + (id % 50), r.getTextValue("sender"));
                fromIndex++;
            }
        }
        assertTrue("records rebuilt from index entries: " + fromIndex, fromIndex > 20);
        /* the indices are not reported as tables */
        assertFalse(job.getTablesNames().contains("idx_sender"));
    }

    /**
     * A record updated with a shorter value: the new cell is written at the end of the freed
     * space, overwriting the end of the former record. The former value is recovered up to the
     * overwritten part (no bytes of the new cell), and the record is marked as truncated.
     */
    @Test
    public void formerValuesOfUpdatedRecordsWithoutForeignBytes() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "upd.db", c -> {
            TestSupport.exec(c, "PRAGMA secure_delete=OFF", "CREATE TABLE msgs(id INTEGER PRIMARY KEY, body TEXT)");
            c.setAutoCommit(false);
            for (int i = 1; i <= 1000; i++) {
                TestSupport.exec(c, "INSERT INTO msgs VALUES(" + i + ", 'msg-" + i + "- " + TestSupport.repeat('x', 100) + "')");
            }
            c.commit();
            c.setAutoCommit(true);
            TestSupport.exec(c, "UPDATE msgs SET body = 'changed' WHERE id % 10 = 0");
        });
        Job job = TestSupport.run(db);
        assertEquals(1000, TestSupport.countRegular(job, "msgs"));
        Pattern valid = Pattern.compile("msg-([0-9]+)- x*");
        Set<Integer> former = new HashSet<>();
        boolean truncated = false;
        for (SqliteRow r : job.getRowsForTable("msgs")) {
            String body = r.getTextValue("body");
            if (!r.isDeletedRow() || body == null || !body.startsWith("msg-"))
                continue;
            Matcher m = valid.matcher(body);
            assertTrue("bytes of an other record in: " + body, m.matches());
            former.add(Integer.parseInt(m.group(1)));
            truncated |= r.isTruncated();
        }
        for (int i = 10; i <= 1000; i += 10) {
            assertTrue("former value of " + i, former.contains(i));
        }
        assertTrue(truncated);
    }

    /** Copies of current records left in free space by b-tree balancing are no deleted records. */
    @Test
    public void copiesOfLiveRecordsAreNotReportedAsDeleted() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "copies.db", c -> {
            TestSupport.exec(c, "PRAGMA secure_delete=OFF",
                    "CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT, n INTEGER) WITHOUT ROWID");
            c.setAutoCommit(false);
            /* random insertion order: many page splits */
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            for (int i = 1; i <= 3000; i++) {
                ids.add(i);
            }
            java.util.Collections.shuffle(ids, new java.util.Random(1));
            for (int i : ids) {
                TestSupport.exec(c, "INSERT INTO kv VALUES('" + String.format("key-%06d", i) + "', '" + body(i, 20 + i % 100)
                        + "', " + i + ")");
            }
            c.commit();
        });
        Job job = TestSupport.run(db);
        assertEquals(3000, TestSupport.countRegular(job, "kv"));
        Set<String> live = new HashSet<>();
        for (SqliteRow r : job.getRowsForTable("kv")) {
            if (!r.isDeletedRow()) {
                live.add(r.toString());
            }
        }
        for (SqliteRow r : job.getRowsForTable("kv")) {
            if (r.isDeletedRow()) {
                assertFalse("copy of a live record reported as deleted: " + r, live.contains(r.toString()));
            }
        }

        Job all = new Job();
        all.keepCopiesOfLiveRecords = true;
        assertEquals(0, all.run(db.toString()));
        long copies = all.getRowsForTable("kv").stream().filter(r -> r.isDeletedRow() && live.contains(r.toString())).count();
        assertTrue("copies left by page splits: " + copies, copies > 0);
    }

    /** Pages of a table whose schema entry was destroyed are not referenced by any b-tree. */
    @Test
    public void pagesWithoutSchema() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "noschema.db", c -> {
            TestSupport.exec(c, "CREATE TABLE keep(a TEXT)", "INSERT INTO keep VALUES('x')",
                    "CREATE TABLE lost(id INTEGER PRIMARY KEY, ts INTEGER, body TEXT)", "CREATE INDEX lost_ts ON lost(ts)");
            c.setAutoCommit(false);
            for (int i = 1; i <= 1000; i++) {
                TestSupport.exec(c, "INSERT INTO lost VALUES(" + i + ", " + (1600000000000L + i) + ", '" + body(i, 30) + "')");
            }
            c.commit();
        });
        /* destroy the schema entry of the table 'lost' */
        byte[] b = Files.readAllBytes(db);
        String s = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
        int i = s.indexOf("CREATE TABLE lost");
        for (int k = 0; k < 17; k++) {
            b[i + k] = 0;
        }
        int j = s.indexOf("lostlost");
        for (int k = 0; k < 8; k++) {
            b[j + k] = 0;
        }
        Files.write(db, b);

        Job job = TestSupport.run(db);
        assertFalse(job.headers.containsKey("lost"));
        Set<Integer> ids = markers(job, "__UNASSIGNED", true);
        assertEquals(1000, ids.size());
        /* the pages of the index (still in the schema) are not reported */
        assertFalse(job.getTablesNames().contains("lost_ts"));
        assertEquals(1, TestSupport.countRegular(job, "keep"));
    }

    /** A database without lost schema does not report anything extra. */
    @Test
    public void noUnreferencedPagesInConsistentDatabase() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "ok.db", c -> {
            TestSupport.exec(c, "CREATE TABLE t(id INTEGER PRIMARY KEY, body TEXT, data BLOB)", "CREATE INDEX t_body ON t(body)");
            c.setAutoCommit(false);
            for (int i = 1; i <= 2000; i++) {
                TestSupport.exec(c, "INSERT INTO t VALUES(" + i + ", '" + body(i, 50) + "', "
                        + (i % 100 == 0 ? "randomblob(9000)" : "NULL") + ")");
            }
            c.commit();
        });
        Job job = TestSupport.run(db);
        assertEquals(2000, TestSupport.countRegular(job, "t"));
        assertEquals(0, TestSupport.countDeleted(job, "t"));
        assertFalse(job.getTablesNames().contains("__UNASSIGNED"));
    }
}
