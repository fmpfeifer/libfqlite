package fqlite.base;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ValueDecodingTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static SqliteRow single(Job job, String table) {
        List<SqliteRow> rows = job.getRowsForTable(table);
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    @Test
    public void negativeIntegersOfAllSizes() throws Exception {
        long[] values = { -5, -300, -100000, -10000000L, -1000000000000L, -1000000000000000000L, 8388607, -8388608,
                140737488355327L, -140737488355328L };
        StringBuilder create = new StringBuilder("CREATE TABLE t(id INTEGER PRIMARY KEY");
        StringBuilder insert = new StringBuilder("INSERT INTO t VALUES(1");
        for (int i = 0; i < values.length; i++) {
            create.append(", c").append(i).append(" INTEGER");
            insert.append(", ").append(values[i]);
        }
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "neg.db",
                c -> TestSupport.exec(c, create.append(")").toString(), insert.append(")").toString()));
        SqliteRow r = single(TestSupport.run(db), "t");
        for (int i = 0; i < values.length; i++) {
            assertEquals("c" + i, values[i], r.getIntValue("c" + i));
        }
    }

    @Test
    public void utf16Database() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "utf16.db", c -> TestSupport.exec(c,
                "PRAGMA encoding='UTF-16le'", "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)",
                "INSERT INTO t VALUES(1, 'ação ñ 日本')"));
        Job job = TestSupport.run(db);
        assertEquals(StandardCharsets.UTF_16LE, job.db_encoding);
        assertEquals("ação ñ 日本", single(job, "t").getTextValue("v"));
    }

    /** Timestamps converted to a date string still return their numeric value. */
    @Test
    public void convertedTimestampKeepsNumericValue() throws Exception {
        long ts = 1600000000000L;
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "ts.db", c -> TestSupport.exec(c,
                "CREATE TABLE t(id INTEGER PRIMARY KEY, ts INTEGER, f REAL)",
                "INSERT INTO t VALUES(1, " + ts + ", 2.5)"));
        boolean saved = Global.CONVERT_DATETIME;
        try {
            Global.CONVERT_DATETIME = true;
            SqliteRow r = single(TestSupport.run(db), "t");
            assertEquals(ts, r.getIntValue("ts"));
            /* the text value is still the converted date (compatibility) */
            assertNotEquals(String.valueOf(ts), r.getTextValue("ts"));
            assertEquals(2.5, r.getFloatValue("f"), 0.0);
        } finally {
            Global.CONVERT_DATETIME = saved;
        }
    }

    @Test
    public void rowToStringSeparatesColumns() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "str.db", c -> TestSupport.exec(c,
                "CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT, b TEXT)", "INSERT INTO t VALUES(1, 'x', 'y')"));
        assertEquals("1;x;y", single(TestSupport.run(db), "t").toString());
    }

    @Test
    public void writeResultsTruncatesExistingFile() throws Exception {
        Path db = TestSupport.createDb(tmp.getRoot().toPath(), "w.db",
                c -> TestSupport.exec(c, "CREATE TABLE t(id INTEGER PRIMARY KEY)"));
        Job job = TestSupport.run(db);
        Path out = tmp.getRoot().toPath().resolve("out.csv");
        job.writeResultsToFile(out.toString(), new String[] { "a long line that is longer\n" });
        String[] lines = { "b\n", "a\n" };
        job.writeResultsToFile(out.toString(), lines);
        assertEquals("a\nb\n", new String(Files.readAllBytes(out), StandardCharsets.UTF_8));
        /* the array of the caller is not changed */
        assertArrayEquals(new String[] { "b\n", "a\n" }, lines);
    }

    @Test
    public void elementFromString() {
        SqliteElementData d = new SqliteElementData("日本", StandardCharsets.UTF_16BE);
        assertEquals("日本", d.toString());
        assertEquals("日本", d.getObject());
        assertTrue(SqliteElement.isStringContent("plain text".getBytes(StandardCharsets.US_ASCII)));
    }
}
