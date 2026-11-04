package fqlite.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import fqlite.base.Job;
import fqlite.descriptor.IndexDescriptor;
import fqlite.descriptor.TableDescriptor;

public class SchemaParserTest {

    private static TableDescriptor table(String sql) {
        return new TableParser().parseCREATETABLEStatement(sql);
    }

    @Test
    public void typesAreAlignedWithColumns() {
        TableDescriptor td = table("CREATE TABLE t(id INTEGER PRIMARY KEY, a, b TEXT, c DATETIME, d BLOB)");
        assertEquals(Arrays.asList("id", "a", "b", "c", "d"), td.columnnames);
        assertEquals(5, td.getColumntypes().size());
        assertEquals(5, td.sqltypes.size());
        assertEquals("TEXT", td.getColumntypes().get(2));
        assertEquals("BLOB", td.getColumntypes().get(4));
        assertEquals("id", td.rowidcolumn);
    }

    @Test
    public void integerPrimaryKeyIsRowidAlias() {
        assertEquals("id", table("CREATE TABLE t(name TEXT, id INTEGER PRIMARY KEY)").rowidcolumn);
        assertEquals("id", table("CREATE TABLE t(id INTEGER, name TEXT, PRIMARY KEY(id))").rowidcolumn);
        /* only the exact type name INTEGER makes a column an alias for the rowid */
        assertNull(table("CREATE TABLE t(id INT PRIMARY KEY, name TEXT)").rowidcolumn);
        assertNull(table("CREATE TABLE t(id INTEGER, name TEXT)").rowidcolumn);
    }

    @Test
    public void namedTableConstraint() {
        TableDescriptor td = table("CREATE TABLE users(id INTEGER, name TEXT, CONSTRAINT pk PRIMARY KEY (id))");
        assertEquals(Arrays.asList("id", "name"), td.columnnames);
        assertEquals(2, td.getColumntypes().size());
    }

    @Test
    public void withoutRowidIsCaseInsensitive() {
        Job job = new Job();
        job.schemaParser.parse(job, "t", 2, "CREATE TABLE t(k TEXT PRIMARY KEY, v) without rowid");
        assertFalse(job.headers.get("t").ROWID);
        job.schemaParser.parse(job, "u", 3, "CREATE TABLE u(k TEXT PRIMARY KEY, v)");
        assertTrue(job.headers.get("u").ROWID);
    }

    @Test
    public void uniqueIndexIsRegistered() {
        Job job = new Job();
        job.schemaParser.parse(job, "t", 4, "CREATE UNIQUE INDEX idx_name ON t(name)");
        IndexDescriptor id = job.indices.get("idx_name");
        assertNotNull(id);
        assertEquals(4, id.getRootOffset());
        assertEquals("t", id.tablename);
    }
}
