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
}
