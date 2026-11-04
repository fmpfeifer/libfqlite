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
