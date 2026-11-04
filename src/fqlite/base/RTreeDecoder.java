package fqlite.base;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import fqlite.descriptor.TableDescriptor;

/**
 * Decodes the entries of an R*Tree virtual table from the records of its
 * {@code <name>_node} shadow table.
 *
 * A node is stored as a BLOB: 2 bytes tree depth, 2 bytes number of cells,
 * followed by the cells. Each cell holds a 64-bit rowid and two 32-bit
 * coordinates (float for rtree, integer for rtree_i32) per dimension.
 */
final class RTreeDecoder {

    private static final String NODE_SUFFIX = "_node";

    private RTreeDecoder() {
    }

    static List<SqliteInternalRow> decode(Job job, SqliteInternalRow row) {
        String tableName = row.getTableName();
        if (tableName == null || !tableName.endsWith(NODE_SUFFIX)) {
            return Collections.emptyList();
        }
        String vtname = tableName.substring(0, tableName.length() - NODE_SUFFIX.length());
        TableDescriptor tds = job.virtualTables.get(vtname);
        if (tds == null || row.getRowData().size() < 2 || row.getRowData().get(1) == null) {
            return Collections.emptyList();
        }
        byte[] binary = row.getRowData().get(1).getBlobValue();
        if (binary == null || binary.length < 4) {
            return Collections.emptyList();
        }
        boolean integerCoords = "rtree_i32".equalsIgnoreCase(tds.getModulename());
        int coordinates = tds.columnnames.size() - 1;
        List<SqliteInternalRow> result = new ArrayList<>();
        ByteBuffer bf = ByteBuffer.wrap(binary);
        try {
            /* skip the tree depth */
            bf.getShort();
            /* total number of entries of this node */
            int entries = bf.getShort() & 0xFFFF;
            for (int e = 0; e < entries; e++) {
                SqliteInternalRow vrow = new SqliteInternalRow();
                vrow.setTableName(vtname);
                vrow.setRecordType("VT");
                vrow.setOffset(row.getOffset());
                vrow.setSource(row.getSource());
                // The first column is always a 64-bit signed integer primary key.
                vrow.append(new SqliteElementData(bf.getLong(), job.db_encoding));
                for (int c = 0; c < coordinates; c++) {
                    if (integerCoords) {
                        vrow.append(new SqliteElementData((long) bf.getInt(), job.db_encoding));
                    } else {
                        vrow.append(new SqliteElementData((double) bf.getFloat(), job.db_encoding));
                    }
                }
                result.add(vrow);
            }
        } catch (BufferUnderflowException e) {
            /* truncated node - return what could be decoded */
        }
        return result;
    }
}
