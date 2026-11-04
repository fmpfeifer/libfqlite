package fqlite.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BasicRecoveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readsRegularRowsOfSmallDatabase() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "small.db", c -> {
            TestSupport.exec(c, "CREATE TABLE person(id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
            for (int i = 0; i < 20; i++) {
                TestSupport.exec(c, "INSERT INTO person(name, age) VALUES('person" + i + "', " + (20 + i) + ")");
            }
        });
        Job job = TestSupport.run(db);
        assertTrue(job.headers.containsKey("person"));
        assertEquals(20, TestSupport.countRegular(job, "person"));
        List<String> names = TestSupport.texts(job, "person", "name");
        assertTrue(names.contains("person0"));
        assertTrue(names.contains("person19"));
    }

    @Test
    public void recoversDeletedRows() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "deleted.db", c -> {
            TestSupport.exec(c, "PRAGMA secure_delete=OFF",
                    "CREATE TABLE person(id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
            for (int i = 0; i < 20; i++) {
                TestSupport.exec(c, "INSERT INTO person(name, age) VALUES('person" + i + "', " + (20 + i) + ")");
            }
            TestSupport.exec(c, "DELETE FROM person WHERE id % 2 = 0");
        });
        Job job = TestSupport.run(db);
        assertEquals(10, TestSupport.countRegular(job, "person"));
        assertTrue("some deleted rows should be recovered", TestSupport.countDeleted(job, "person") > 0);
    }

    /**
     * Stale cell pointers at the end of the unallocated area must not make the recovery skip the
     * whole cell content area (all freeblocks of the page were lost).
     */
    @Test
    public void deletedShortRecordsInFreeblocks() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "short.db", c -> {
            TestSupport.exec(c, "PRAGMA secure_delete=OFF", "CREATE TABLE msgs(id INTEGER PRIMARY KEY, body TEXT, rd INTEGER)");
            c.setAutoCommit(false);
            for (int i = 1; i <= 3000; i++) {
                TestSupport.exec(c, "INSERT INTO msgs VALUES(" + i + ", 'msg-" + i + "- " + TestSupport.repeat('a', 2 + (i * 7) % 28)
                        + "', " + (i % 2) + ")");
            }
            c.commit();
            c.setAutoCommit(true);
            TestSupport.exec(c, "DELETE FROM msgs WHERE id % 4 = 0");
        });
        Job job = TestSupport.run(db);
        java.util.Set<String> recovered = new java.util.HashSet<>();
        for (SqliteRow r : job.getRowsForTable("msgs")) {
            String body = r.getTextValue("body");
            if (r.isDeletedRow() && body != null && body.startsWith("msg-")) {
                recovered.add(body.substring(0, body.indexOf(' ')));
            }
        }
        int found = 0;
        for (int i = 4; i <= 3000; i += 4) {
            if (recovered.contains("msg-" + i + "-")) {
                found++;
            }
        }
        assertTrue("recovered " + found + " of 750", found >= 745);
    }
}
