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

    /** The schema of a dropped table can still be found in the sqlite_master page. */
    @Test
    public void droppedTableSchema() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "dropped.db", c -> {
            /*
             * long statement: the payload length needs 2 bytes, so the freeblock header written over
             * the deleted cell destroys only the header size byte. 'gone' is created first, so its cell
             * is not at the start of the cell content area (a freeblock is written).
             */
            TestSupport.exec(c, "PRAGMA secure_delete=OFF",
                    "CREATE TABLE gone(id INTEGER PRIMARY KEY, secret_column TEXT, other_column TEXT,"
                            + " more_column TEXT, again_column TEXT, yet_another_column TEXT, last_column TEXT)",
                    "CREATE TABLE keep_me(id INTEGER PRIMARY KEY, a TEXT, b TEXT, c TEXT, d TEXT, e TEXT)",
                    "INSERT INTO gone(secret_column) VALUES('hidden')", "DROP TABLE gone");
        });
        Job job = TestSupport.run(db);
        assertNotNull(job.headers.get("keep_me"));
        assertNotNull("dropped table schema", job.headers.get("gone"));
    }

    @Test
    public void schemaOfUtf16Databases() throws Exception {
        for (String enc : new String[] { "UTF-16le", "UTF-16be" }) {
            Path db = TestSupport.createDb(tmp.getRoot().toPath(), enc + ".db", c -> TestSupport.exec(c,
                    "PRAGMA encoding='" + enc + "'", "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)",
                    "CREATE INDEX idx_v ON t(v)", "INSERT INTO t VALUES(1, 'abc')"));
            Job job = TestSupport.run(db);
            assertNotNull(enc, job.headers.get("t"));
            assertNotNull(enc, job.indices.get("idx_v"));
        }
    }
}
