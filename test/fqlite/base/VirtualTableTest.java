package fqlite.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VirtualTableTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Map<Long, SqliteRow> byId(List<SqliteRow> rows) {
        return rows.stream().collect(Collectors.toMap(r -> r.getIntValue("id"), r -> r, (a, b) -> a));
    }

    @Test
    public void rtreeEntriesAreDecoded() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "rtree.db", c -> {
            TestSupport.exec(c, "CREATE VIRTUAL TABLE demo USING rtree(id, minX, maxX, minY, maxY)");
            for (int i = 1; i <= 3; i++) {
                TestSupport.exec(c, "INSERT INTO demo VALUES(" + i + ", " + i + ".5, " + (i + 1) + ".5, -" + i
                        + ".25, " + i + ".75)");
            }
        });
        Job job = TestSupport.run(db);
        assertTrue(job.virtualTables.containsKey("demo"));
        Map<Long, SqliteRow> rows = byId(job.getRowsForTable("demo"));
        assertEquals(3, rows.size());
        SqliteRow r2 = rows.get(2L);
        assertEquals(2.5, r2.getFloatValue("minX"), 1e-6);
        assertEquals(3.5, r2.getFloatValue("maxX"), 1e-6);
        assertEquals(-2.25, r2.getFloatValue("minY"), 1e-6);
        assertEquals(2.75, r2.getFloatValue("maxY"), 1e-6);
    }

    @Test
    public void rtreeI32EntriesAreDecoded() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "rtreei32.db", c -> {
            TestSupport.exec(c, "CREATE VIRTUAL TABLE demo USING rtree_i32(id, minX, maxX)");
            TestSupport.exec(c, "INSERT INTO demo VALUES(7, -10, 20)");
        });
        Job job = TestSupport.run(db);
        Map<Long, SqliteRow> rows = byId(job.getRowsForTable("demo"));
        assertEquals(1, rows.size());
        assertEquals(-10, rows.get(7L).getIntValue("minX"));
        assertEquals(20, rows.get(7L).getIntValue("maxX"));
    }
}
