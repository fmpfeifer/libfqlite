package fqlite.base;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fqlite.descriptor.IndexDescriptor;
import fqlite.descriptor.TableDescriptor;
import fqlite.util.Auxiliary;
import fqlite.util.FreeblockReconstructor;

/**
 * Recovers deleted records from the entries of the indices of rowid tables.
 *
 * An index entry holds the values of the indexed columns and the rowid of the record.
 * When the record of the table was overwritten, the deleted index entry may still be
 * there (in a freeblock of an index page or on a freelist page). Such entries are
 * reported as partial records of the table (record type
 * {@link Global#DELETED_RECORD_IN_PAGE} + {@link Global#INDEX_ENTRY}): only the indexed
 * columns and the rowid are known, the other columns are NULL.
 */
final class IndexEntryRecovery {

    private final Job job;
    private final Auxiliary aux;

    /** An index of a rowid table usable for the recovery. */
    private static final class Index {
        IndexDescriptor id;
        TableDescriptor table;
        /* the index as table without rowid: indexed columns + rowid */
        TableDescriptor asTable;
        /* table column of each index column (the rowid is the last one) */
        int[] tableColumns;
    }

    private final List<Index> indices = new ArrayList<>();

    /* pages (freelist) whose records were attributed to an index */
    final Set<Integer> attributedPages = new HashSet<>();

    /* records rebuilt from index entries */
    final List<SqliteInternalRow> rows = new ArrayList<>();

    IndexEntryRecovery(Job job, Auxiliary aux) {
        this.job = job;
        this.aux = aux;
        for (IndexDescriptor id : job.indices.values()) {
            Index ix = prepare(id);
            if (ix != null)
                indices.add(ix);
        }
    }

    private Index prepare(IndexDescriptor id) {
        TableDescriptor td = id.tablename == null ? null : job.headers.get(id.tablename);
        if (td == null || !td.ROWID || td.isVirtual() || id.columnnames.size() != id.columntypes.size()
                || id.columnnames.size() < 2 || !"rowid".equals(id.columnnames.get(id.columnnames.size() - 1)))
            return null;
        int n = id.columnnames.size();
        Index ix = new Index();
        ix.id = id;
        ix.table = td;
        ix.tableColumns = new int[n];
        int mapped = 0;
        for (int i = 0; i < n - 1; i++) {
            ix.tableColumns[i] = td.columnnames.indexOf(id.columnnames.get(i));
            if (ix.tableColumns[i] >= 0)
                mapped++;
        }
        ix.tableColumns[n - 1] = td.rowidcolumn == null ? -1 : td.columnnames.indexOf(td.rowidcolumn);
        /* expression indices: no column of the table is known */
        if (mapped == 0)
            return null;
        List<String> names = new ArrayList<>(id.columnnames);
        List<String> types = new ArrayList<>(id.columntypes);
        List<String> constraints = new ArrayList<>();
        for (int i = 0; i < n; i++)
            constraints.add(i == n - 1 ? "NOTNULL" : "");
        ix.asTable = new TableDescriptor(id.idxname, "", types, types, names, constraints, null, null, true);
        ix.asTable.tblname = id.idxname;
        return ix;
    }

    boolean isEmpty() {
        return indices.isEmpty();
    }

    /**
     * Deleted entries in the freeblocks of the leaf pages of the index b-trees.
     *
     * @param leafPages leaf pages of each index
     */
    void recoverFromIndexPages(Map<IndexDescriptor, Collection<Integer>> leafPages) throws IOException {
        for (Index ix : indices) {
            Collection<Integer> pages = leafPages.get(ix.id);
            if (pages == null)
                continue;
            for (int pn : pages) {
                ByteBuffer page = job.readPageWithNumber(pn - 1, job.ps);
                if (page == null)
                    continue;
                long pageOffset = (long) (pn - 1) * job.ps;
                if (page.get(0) != 0x02 && page.get(0) != 0x0A)
                    continue;
                /* rebalancing leaves former cells in the unallocated area */
                int header = page.get(0) == 0x02 ? 12 : 8;
                int cells = page.getShort(3) & 0xFFFF;
                int content = page.getShort(5) & 0xFFFF;
                if (content == 0)
                    content = 65536;
                scanUnallocated(ix, page, header + 2 * cells, Math.min(content, page.limit()), pageOffset);
                if (page.get(0) == 0x02) {
                    recoverFromInteriorPage(ix, page, pageOffset);
                    continue;
                }
                FreeblockReconstructor.recoverFreeblocks(aux, page, ix.asTable, true, null, new BitSet(job.ps),
                        pageOffset, RecordSource.DATABASE, entry -> addEntry(ix, entry));
            }
        }
    }

    /**
     * Interior pages of an index b-tree hold entries too. A cell starts with the 4 byte
     * page number of its left child, which is exactly what the freeblock header overwrites:
     * the index record of a deleted cell is intact behind it.
     */
    private void recoverFromInteriorPage(Index ix, ByteBuffer page, long pageOffset) {
        int usable = Math.min(page.limit(), job.getUsablePageSize());
        Set<Integer> seen = new HashSet<>();
        int fb = page.getShort(1) & 0xFFFF;
        while (fb >= 12 && fb + 4 <= usable && seen.add(fb)) {
            int next = page.getShort(fb) & 0xFFFF;
            int end = Math.min(usable, fb + (page.getShort(fb + 2) & 0xFFFF));
            /* the freeblock may hold several deleted cells: child page number + record */
            int p = fb;
            while (p + 4 < end) {
                int record = p + 4;
                long[] serials = readSerials(page, record);
                if (serials == null || !matches(ix, serials))
                    break;
                int[] q = { record };
                long payload = varint(page, q);
                /* records spilled to overflow pages are not handled */
                if (payload <= 0 || q[0] + payload > end)
                    break;
                SqliteInternalRow entry = readEntry(page, record, serials, pageOffset);
                if (entry == null)
                    break;
                addEntry(ix, entry);
                p = q[0] + (int) payload;
            }
            if (next != 0 && next <= fb)
                break;
            fb = next;
        }
    }

    /** Search complete index records in the unallocated area [from, to) of an index page. */
    private void scanUnallocated(Index ix, ByteBuffer page, int from, int to, long pageOffset) {
        int p = from;
        while (p < to - 3) {
            long[] serials = readSerials(page, p);
            if (serials != null && matches(ix, serials)) {
                int[] q = { p };
                long payload = varint(page, q);
                if (payload > 0 && q[0] + payload <= to) {
                    SqliteInternalRow entry = readEntry(page, p, serials, pageOffset);
                    if (entry != null && validText(entry)) {
                        addEntry(ix, entry);
                        p = q[0] + (int) payload;
                        continue;
                    }
                }
            }
            p++;
        }
    }

    /** Text values must be readable (no replacement or control characters). */
    private static boolean validText(SqliteInternalRow entry) {
        for (SqliteElementData d : entry.getRowData()) {
            Object o = d == null ? null : d.getObject(false);
            if (o instanceof String) {
                String s = (String) o;
                for (int i = 0; i < s.length(); i++) {
                    char ch = s.charAt(i);
                    if (ch == '�' || (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t'))
                        return false;
                }
            }
        }
        return true;
    }

    private boolean matches(Index ix, long[] serials) {
        List<String> types = ix.id.columntypes;
        if (types.size() != serials.length)
            return false;
        for (int i = 0; i < serials.length; i++) {
            if (!typeMatches(types.get(i), serials[i]) || (i == serials.length - 1 && !isInteger(serials[i])))
                return false;
        }
        return true;
    }

    /**
     * Freelist pages which were leaf pages of an index: their records are attributed to the
     * index whose columns match.
     *
     * @param freelistPages the pages on the freelist
     */
    void recoverFromFreelist(Collection<Integer> freelistPages) throws IOException {
        for (int pn : freelistPages) {
            ByteBuffer page = job.readPageWithNumber(pn - 1, job.ps);
            if (page == null || page.get(0) != 0x0A)
                continue;
            int cells = page.getShort(3) & 0xFFFF;
            List<SqliteInternalRow> found = new ArrayList<>();
            Index owner = null;
            for (int i = 0; i < cells && 8 + 2 * i + 2 <= page.limit(); i++) {
                int cell = page.getShort(8 + 2 * i) & 0xFFFF;
                long[] serials = readSerials(page, cell);
                if (serials == null)
                    continue;
                Index ix = matching(serials);
                if (ix == null || (owner != null && owner != ix)) {
                    owner = null;
                    found.clear();
                    break;
                }
                owner = ix;
                SqliteInternalRow entry = readEntry(page, cell, serials, (long) (pn - 1) * job.ps);
                if (entry != null)
                    found.add(entry);
            }
            if (owner != null && !found.isEmpty()) {
                attributedPages.add(pn);
                for (SqliteInternalRow e : found)
                    addEntry(owner, e);
            }
        }
    }

    /** Serial types of the index record of a leaf cell, null if not readable. */
    private long[] readSerials(ByteBuffer page, int cell) {
        int[] p = { cell };
        long payload = varint(page, p);
        int hp = p[0];
        long hs = varint(page, p);
        if (payload < 2 || hs < 2 || hs > payload || hp + hs > page.limit())
            return null;
        List<Long> serials = new ArrayList<>();
        while (p[0] < hp + hs) {
            long st = varint(page, p);
            if (st < 0 || st == 10 || st == 11)
                return null;
            serials.add(st);
        }
        if (p[0] != hp + hs)
            return null;
        long[] r = new long[serials.size()];
        for (int i = 0; i < r.length; i++)
            r[i] = serials.get(i);
        return r;
    }

    /** The only index whose column types match the serial types. */
    private Index matching(long[] serials) {
        Index found = null;
        for (Index ix : indices) {
            List<String> types = ix.id.columntypes;
            if (types.size() != serials.length)
                continue;
            boolean ok = true;
            for (int i = 0; i < serials.length && ok; i++)
                ok = typeMatches(types.get(i), serials[i]) && (i < serials.length - 1 || isInteger(serials[i]));
            if (ok) {
                if (found != null)
                    return null;
                found = ix;
            }
        }
        return found;
    }

    private static boolean isInteger(long st) {
        return (st >= 1 && st <= 6) || st == 8 || st == 9;
    }

    private static boolean typeMatches(String type, long st) {
        switch (type) {
            case "INT":
                return st == 0 || isInteger(st);
            case "TEXT":
                return st == 0 || (st >= 13 && st % 2 == 1);
            case "REAL":
                return st == 0 || st == 7 || isInteger(st);
            default:
                return true;
        }
    }

    private SqliteInternalRow readEntry(ByteBuffer page, int cell, long[] serials, long pageOffset) {
        int[] p = { cell };
        varint(page, p);
        int hp = p[0];
        long hs = varint(page, p);
        byte[] header = new byte[(int) hs - (p[0] - hp)];
        for (int i = 0; i < header.length; i++)
            header[i] = page.get(p[0] + i);
        SqliteElement[] elements = Auxiliary.convertHeaderToSqliteElements(header, job.db_encoding);
        if (elements == null || elements.length != serials.length)
            return null;
        int pos = hp + (int) hs;
        SqliteInternalRow row = new SqliteInternalRow();
        row.setOffset(pageOffset + cell);
        for (SqliteElement en : elements) {
            if (en.type == fqlite.types.SerialTypes.INT0) {
                row.append(new SqliteElementData(en, 0));
            } else if (en.type == fqlite.types.SerialTypes.INT1) {
                row.append(new SqliteElementData(en, 1));
            } else if (en.length == 0) {
                row.append(new SqliteElementData(null, job.db_encoding));
            } else {
                if (pos + en.length > page.limit())
                    return null;
                byte[] value = new byte[en.length];
                for (int i = 0; i < value.length; i++)
                    value[i] = page.get(pos + i);
                pos += en.length;
                row.append(new SqliteElementData(en, value));
            }
        }
        return row;
    }

    private static long varint(ByteBuffer page, int[] p) {
        long v = 0;
        for (int i = 0; i < 9; i++) {
            if (p[0] >= page.limit())
                return -1;
            int b = page.get(p[0]++) & 0xFF;
            if (i == 8)
                return (v << 8) | b;
            v = (v << 7) | (b & 0x7F);
            if ((b & 0x80) == 0)
                return v;
        }
        return v;
    }

    /** Convert an index entry into a partial record of the table. */
    private void addEntry(Index ix, SqliteInternalRow entry) {
        List<SqliteElementData> values = entry.getRowData();
        int n = ix.tableColumns.length;
        if (values.size() != n || values.get(n - 1) == null)
            return;
        Object rid = values.get(n - 1).getObject(false);
        if (!(rid instanceof Number))
            return;
        long rowid = ((Number) rid).longValue();
        /* rowids of the records are positive (an other value means a wrong record) */
        if (rowid <= 0)
            return;
        /* an entry without any indexed value tells nothing */
        boolean known = false;
        for (int i = 0; i < n - 1 && !known; i++)
            known = ix.tableColumns[i] >= 0 && values.get(i) != null && values.get(i).getObject(false) != null;
        if (!known)
            return;
        SqliteInternalRow row = new SqliteInternalRow();
        row.setTableName(ix.table.tblname);
        row.setRecordType(Global.DELETED_RECORD_IN_PAGE + Global.INDEX_ENTRY);
        row.setOffset(entry.getOffset());
        row.setSource(entry.getSource());
        row.setRowId(rowid);
        int columns = ix.table.columnnames.size();
        for (int c = 0; c < columns; c++) {
            SqliteElementData value = null;
            for (int i = 0; i < n; i++) {
                if (ix.tableColumns[i] == c) {
                    value = (i == n - 1) ? new SqliteElementData(rowid, job.db_encoding) : values.get(i);
                    break;
                }
            }
            row.append(value != null ? value : new SqliteElementData(null, job.db_encoding));
        }
        synchronized (rows) {
            rows.add(row);
        }
    }

    /**
     * Keep only the entries which tell something new: no current record with this rowid,
     * no other deleted record of the table with this rowid or with the same indexed values.
     *
     * @param records all other records of the recovery
     * @return the records rebuilt from index entries to report
     */
    List<SqliteInternalRow> select(Collection<SqliteInternalRow> records) {
        Map<String, Set<Long>> liveRowids = new HashMap<>();
        Map<String, Set<Long>> deletedRowids = new HashMap<>();
        Set<String> deletedValues = new HashSet<>();
        for (SqliteInternalRow r : records) {
            String t = r.getTableName();
            if (!r.isDeletedRow()) {
                if (r.getRowId() != Long.MIN_VALUE)
                    liveRowids.computeIfAbsent(t, k -> new HashSet<>()).add(r.getRowId());
            } else {
                if (r.getRowId() != Long.MIN_VALUE)
                    deletedRowids.computeIfAbsent(t, k -> new HashSet<>()).add(r.getRowId());
                for (Index ix : indices) {
                    if (ix.table.tblname.equals(t))
                        deletedValues.add(indexedKey(ix, r));
                }
            }
        }
        /* the largest rowid known for each table: much larger values come from wrong records */
        Map<String, Long> maxRowid = new HashMap<>();
        for (SqliteInternalRow r : records) {
            if (r.getRowId() != Long.MIN_VALUE)
                maxRowid.merge(r.getTableName(), r.getRowId(), Math::max);
        }
        List<SqliteInternalRow> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SqliteInternalRow r : rows) {
            String t = r.getTableName();
            long rowid = r.getRowId();
            long max = maxRowid.getOrDefault(t, 0L);
            if (rowid > Math.max(2 * max, max + 10000))
                continue;
            boolean current = liveRowids.getOrDefault(t, Set.of()).contains(rowid);
            boolean deleted = deletedRowids.getOrDefault(t, Set.of()).contains(rowid);
            boolean duplicate = !seen.add(t + "\u0000" + rowid);
            if (current || deleted || duplicate)
                continue;
            boolean known = false;
            for (Index ix : indices) {
                if (ix.table.tblname.equals(t) && deletedValues.contains(indexedKey(ix, r))) {
                    known = true;
                    break;
                }
            }
            if (!known)
                result.add(r);
        }
        return result;
    }

    /** Key of the values of the indexed columns of a record of the table. */
    private static String indexedKey(Index ix, SqliteInternalRow r) {
        StringBuilder sb = new StringBuilder(ix.id.idxname);
        List<SqliteElementData> data = r.getRowData();
        for (int i = 0; i < ix.tableColumns.length - 1; i++) {
            int c = ix.tableColumns[i];
            sb.append('\u0000');
            if (c >= 0 && c < data.size() && data.get(c) != null)
                sb.append(data.get(c).rawKey());
        }
        return sb.toString();
    }
}
