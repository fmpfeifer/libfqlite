package fqlite.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Results of larger databases: b-trees with several levels, indices and deleted records.
 */
public class CompatibilityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The b-tree of a WITHOUT ROWID table is an index b-tree: its interior pages hold records too. */
    @Test
    public void withoutRowidTableWithInteriorPages() throws Exception {
        int n = 3000;
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "wr.db", c -> {
            TestSupport.exec(c, "CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT, n INTEGER) WITHOUT ROWID");
            c.setAutoCommit(false);
            for (int i = 0; i < n; i++) {
                TestSupport.exec(c, "INSERT INTO kv VALUES('key-" + String.format("%06d", i) + "', '"
                        + TestSupport.repeat('v', 50 + (i * 37) % 400) + "', " + i + ")");
            }
            c.commit();
            c.setAutoCommit(true);
        });
        Job job = TestSupport.run(db);
        Set<Long> ns = job.getRowsForTable("kv").stream().filter(r -> !r.isDeletedRow())
                .map(r -> r.getIntValue("n")).collect(Collectors.toSet());
        assertEquals(n, ns.size());
        assertEquals(n, TestSupport.countRegular(job, "kv"));
    }

    /** Index entries are not reported as rows of a table named like the index. */
    @Test
    public void indexEntriesAreNotReportedAsTables() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "idx.db", c -> {
            TestSupport.exec(c, "CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)", "CREATE INDEX idx_name ON t(name)");
            c.setAutoCommit(false);
            for (int i = 0; i < 5000; i++) {
                TestSupport.exec(c, "INSERT INTO t(name) VALUES('name " + i + " " + TestSupport.repeat('n', 40) + "')");
            }
            c.commit();
            c.setAutoCommit(true);
        });
        Job job = TestSupport.run(db);
        assertFalse(job.getTablesNames().contains("idx_name"));
        assertEquals(5000, TestSupport.countRegular(job, "t"));
    }
}
