package fqlite.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SchemaTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Tables whose root page needs more than one byte (&gt; 127) must be found. */
    @Test
    public void tableWithRootPageGreaterThan127() throws Exception {
        int[] root = new int[1];
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "big.db", c -> {
            TestSupport.exec(c, "PRAGMA secure_delete=OFF", "CREATE TABLE filler(x BLOB)");
            for (int i = 0; i < 200; i++) {
                TestSupport.exec(c, "INSERT INTO filler VALUES(randomblob(3000))");
            }
            TestSupport.exec(c, "CREATE TABLE late(id INTEGER PRIMARY KEY, name TEXT)");
            for (int i = 0; i < 50; i++) {
                TestSupport.exec(c, "INSERT INTO late(name) VALUES('name" + i + "')");
            }
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT rootpage FROM sqlite_master WHERE name='late'")) {
                rs.next();
                root[0] = rs.getInt(1);
            }
            TestSupport.exec(c, "DELETE FROM late WHERE id % 2 = 0");
        });
        assertTrue("test setup: root page should be > 127", root[0] > 127);

        Job job = TestSupport.run(db);
        assertNotNull(job.headers.get("late"));
        assertEquals(root[0], job.headers.get("late").getRootOffset());
        assertEquals(25, TestSupport.countRegular(job, "late"));
        assertTrue(TestSupport.countDeleted(job, "late") > 0);
    }
}
