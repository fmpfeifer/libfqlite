package fqlite.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fqlite.base.Global;
import fqlite.base.Job;
import fqlite.base.SqliteElement;
import fqlite.base.SqliteElementData;
import fqlite.base.SqliteInternalRow;
import fqlite.descriptor.TableDescriptor;
import fqlite.types.SerialTypes;

/**
 * Reconstructs the deleted records stored in a freeblock of a b-tree page.
 *
 * When a cell is deleted, its space becomes (part of) a freeblock: the first 4 bytes
 * of the cell are overwritten with the freeblock header (offset of the next freeblock
 * and size). For a record this destroys the payload length, the rowid (tables with
 * rowid), the header size and possibly the first serial types. Adjacent deleted cells
 * are merged into one freeblock.
 *
 * The freeblock gives the exact start and end of the deleted cells. A sequence of
 * cells (intact or with overwritten first bytes) is searched, which fills the freeblock
 * exactly and is consistent with the schema of the table: lost serial types must match
 * the declared column types, the length of the lost payload length field must fit into
 * the overwritten bytes and text values must be valid in the database encoding.
 */
public class FreeblockReconstructor {

    /* maximum number of candidate cells evaluated for one freeblock */
    private static final int MAX_CANDIDATES = 20000;

    /* maximum text/blob length with a 1 or 2 byte serial type */
    private static final int MAX_LEN_1 = 57;
    private static final int MAX_LEN_2 = 8185;

    private final Auxiliary aux;
    private final Job job;
    private final ByteBuffer page;
    private final TableDescriptor td;
    private final boolean indexPage;
    private final int columns;
    private final int rowidColumn;
    /* columns which can not be NULL (NOT NULL, primary key of a WITHOUT ROWID table) */
    private final boolean[] notNull;
    /* text lengths of the live records of the page: column -> length -> count */
    private final Map<Integer, Map<Integer, Integer>> textLengths;
    private int budget;
    private boolean nullForVariable = true;

    private static boolean isVariableType(String type) {
        return "TEXT".equals(type) || "BLOB".equals(type) || "NUMERIC".equals(type);
    }

    /** A cell found in the freeblock. */
    private static final class Cell {
        int start;
        int end;
        long[] serials;
        int dataStart;
        int payload;
        int local;
        int overflow;
        long rowid = Long.MIN_VALUE;
    }

    private static final List<Cell> NO_SOLUTION = Collections.emptyList();

    /**
     * @param aux         the auxiliary object of the job
     * @param page        the b-tree page
     * @param td          the table the page belongs to
     * @param indexPage   true for pages of an index b-tree (WITHOUT ROWID table): no rowid in the cells
     * @param textLengths text lengths of the live records of the page (column -&gt; length -&gt; count), may be null
     */
    public FreeblockReconstructor(Auxiliary aux, ByteBuffer page, TableDescriptor td, boolean indexPage,
            Map<Integer, Map<Integer, Integer>> textLengths) {
        this.aux = aux;
        this.job = aux.getJob();
        this.page = page;
        this.td = td;
        this.indexPage = indexPage;
        this.columns = td.columnnames.size();
        int alias = (indexPage || td.rowidcolumn == null) ? -1 : td.columnnames.indexOf(td.rowidcolumn);
        this.rowidColumn = alias;
        this.notNull = new boolean[columns];
        for (int i = 0; i < columns; i++) {
            String cons = (td.constraints != null && i < td.constraints.size()) ? td.constraints.get(i) : null;
            boolean pk = td.primarykeycolumns != null && td.primarykeycolumns.contains(td.columnnames.get(i));
            notNull[i] = (cons != null && cons.toUpperCase().contains("NOTNULL")) || (!td.ROWID && pk);
        }
        this.textLengths = textLengths == null ? Collections.emptyMap() : textLengths;
    }

    /**
     * Reconstruct the deleted records of all freeblocks of a leaf page.
     *
     * @param aux         the auxiliary object of the job
     * @param page        the page
     * @param td          the table of the page (null: nothing is done)
     * @param indexPage   true for leaf pages of an index b-tree (WITHOUT ROWID table)
     * @param textLengths text lengths of the live records of the page, may be null
     * @param visit       the visited bytes of the page: the freeblocks explained are marked
     * @param pageOffset  offset of the page in its file
     * @param source      the file the page was read from
     * @param sink        receives the records
     * @return the number of records found
     */
    public static int recoverFreeblocks(Auxiliary aux, ByteBuffer page, TableDescriptor td, boolean indexPage,
            Map<Integer, Map<Integer, Integer>> textLengths, java.util.BitSet visit, long pageOffset,
            fqlite.base.RecordSource source, java.util.function.Consumer<SqliteInternalRow> sink) {
        if (td == null || td.isVirtual() || td.columnnames == null || td.tblname.startsWith("__UNASSIGNED"))
            return 0;
        int usable = Math.min(page.limit(), aux.getJob().getUsablePageSize());
        FreeblockReconstructor r = new FreeblockReconstructor(aux, page, td, indexPage, textLengths);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int found = 0;
        int fb = page.getShort(1) & 0xFFFF;
        while (fb >= 8 && fb + 4 <= usable && seen.add(fb)) {
            int next = page.getShort(fb) & 0xFFFF;
            int size = page.getShort(fb + 2) & 0xFFFF;
            int end = fb + size;
            int visited = visit.nextSetBit(fb);
            boolean untouched = visited < 0 || visited >= end;
            if (size >= 4 && end <= usable && untouched) {
                List<SqliteInternalRow> rows = r.reconstruct(fb, end, pageOffset);
                if (rows != null) {
                    for (SqliteInternalRow row : rows) {
                        row.setSource(source);
                        if (!Auxiliary.isEmptyRow(row)) {
                            sink.accept(row);
                            found++;
                        }
                    }
                    visit.set(fb, end);
                }
            }
            if (next != 0 && next <= fb)
                break;
            fb = next;
        }
        return found;
    }

    /**
     * Collect the text lengths of a live record (used to split two lost text columns).
     *
     * @param textLengths the statistics to update
     * @param row         a live record
     */
    public static void addTextLengths(Map<Integer, Map<Integer, Integer>> textLengths, SqliteInternalRow row) {
        List<SqliteElementData> data = row.getRowData();
        for (int i = 0; i < data.size(); i++) {
            Object o = data.get(i) == null ? null : data.get(i).getObject(false);
            if (o instanceof String) {
                int len = data.get(i).getBlobValue().length;
                textLengths.computeIfAbsent(i, k -> new HashMap<>()).merge(len, 1, Integer::sum);
            }
        }
    }

    /**
     * Reconstruct the records of the freeblock.
     *
     * @param start      offset of the freeblock in the page
     * @param end        end of the freeblock (exclusive)
     * @param pageOffset offset of the page in its file (for the offsets of the records)
     * @return the records, or null if the freeblock could not be explained by deleted cells
     */
    public List<SqliteInternalRow> reconstruct(int start, int end, long pageOffset) {
        if (columns == 0 || columns > 120 || td.isVirtual() || end > page.limit() || end - start < 4)
            return null;
        budget = MAX_CANDIDATES;
        List<Cell> cells;
        try {
            cells = solve(start, end, new HashMap<>(), 0);
        } catch (RuntimeException | IOException e) {
            return null;
        }
        if (cells == null)
            return null;
        List<SqliteInternalRow> rows = new ArrayList<>();
        try {
            for (Cell c : cells) {
                SqliteInternalRow row = toRow(c, pageOffset);
                if (row == null)
                    return null;
                rows.add(row);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return rows;
    }

    /* ---------------------------------------------------------------------------- */

    private List<Cell> solve(int pos, int end, Map<Integer, List<Cell>> memo, int depth) throws IOException {
        if (memo.containsKey(pos)) {
            List<Cell> r = memo.get(pos);
            return r == NO_SOLUTION ? null : r;
        }
        List<Cell> result = null;
        if (depth < 64) {
            for (Cell c : candidates(pos, end)) {
                /* a trailing fragment of less than 4 bytes belongs to the freeblock */
                if (c.end >= end - 3 && c.end <= end) {
                    result = Collections.singletonList(c);
                    break;
                }
                if (c.end <= end - 4) {
                    List<Cell> rest = solve(c.end, end, memo, depth + 1);
                    if (rest != null) {
                        result = new ArrayList<>(rest.size() + 1);
                        result.add(c);
                        result.addAll(rest);
                        break;
                    }
                }
                if (budget <= 0)
                    break;
            }
        }
        memo.put(pos, result == null ? NO_SOLUTION : result);
        return result;
    }

    /** Candidate cells starting at pos and ending not after end, the most plausible first. */
    private List<Cell> candidates(int pos, int end) throws IOException {
        List<Cell> list = new ArrayList<>();
        Cell intact = intactCell(pos, end);
        if (intact != null)
            list.add(intact);
        /* the first 4 bytes are overwritten: the header size starts at hp */
        int minPrefix = indexPage ? 1 : 2;
        for (int hp = pos + 3; hp >= pos + minPrefix; hp--) {
            overwrittenCells(pos, hp, end, list);
            if (budget <= 0)
                break;
        }
        selectExact(list, end, intact);
        /* prefer the cells filling the rest of the freeblock */
        list.sort((a, b) -> Boolean.compare(b.end >= end - 3, a.end >= end - 3));
        return list;
    }

    /**
     * Several different cells filling the rest of the freeblock: keep the most plausible one
     * (text lengths of the live records), none if they can not be told apart.
     */
    private void selectExact(List<Cell> list, int end, Cell intact) {
        List<Cell> exact = new ArrayList<>();
        for (Cell c : list) {
            if (c.end >= end - 3 && (exact.isEmpty() || !sameHeader(exact, c)))
                exact.add(c);
        }
        if (exact.size() <= 1)
            return;
        Cell keep = null;
        if (intact != null && exact.contains(intact)) {
            /* the header bytes of this cell are intact */
            keep = intact;
        } else {
            double best = -1, second = -1;
            for (Cell c : exact) {
                double s = score(c);
                if (s > best) {
                    second = best;
                    best = s;
                    keep = c;
                } else if (s > second) {
                    second = s;
                }
            }
            if (best <= second)
                keep = null;
        }
        final Cell chosen = keep;
        list.removeIf(c -> c.end >= end - 3 && c != chosen);
    }

    private static boolean sameHeader(List<Cell> cells, Cell c) {
        for (Cell o : cells) {
            if (o.start == c.start && o.dataStart == c.dataStart && java.util.Arrays.equals(o.serials, c.serials))
                return true;
        }
        return false;
    }

    /** Plausibility of the text lengths compared with the live records of the page. */
    private double score(Cell c) {
        double s = 0;
        for (int i = 0; i < columns; i++) {
            if (c.serials[i] >= 13 && c.serials[i] % 2 == 1)
                s += share(i, serialSize(c.serials[i]));
        }
        return s;
    }

    /** A cell whose first bytes are intact (e.g. not the first cell of a merged freeblock). */
    private Cell intactCell(int pos, int end) throws IOException {
        budget--;
        int[] p = { pos };
        long payload = varint(p, end);
        long rowid = Long.MIN_VALUE;
        if (!indexPage)
            rowid = varint(p, end);
        int hp = p[0];
        long hs = varint(p, end);
        if (payload < 1 || rowid == -2 || hs < 1 || hs > payload || hs > 127 * 9)
            return null;
        int headerEnd = hp + (int) hs;
        long[] serials = new long[columns];
        for (int i = 0; i < columns; i++) {
            if (p[0] >= headerEnd)
                return null;
            serials[i] = varint(p, headerEnd);
            if (serials[i] < 0)
                return null;
        }
        if (p[0] != headerEnd)
            return null;
        Cell c = buildCell(pos, hp, (int) hs, serials, end);
        if (c == null || c.payload != payload)
            return null;
        c.rowid = rowid;
        return c;
    }

    /** A lost serial type: fixed (1 byte varint), 2 byte varint, or the high byte of a 2 byte varint. */
    private static final int LOST_1 = 1, LOST_2 = 2, LOST_HIGH = 3;

    /** Cells whose first 4 bytes were overwritten, with the header size at hp. */
    private void overwrittenCells(int pos, int hp, int end, List<Cell> out) throws IOException {
        /* number of serial type bytes lost in the overwritten bytes */
        int lostBytes = pos + 3 - hp;
        /*
         * possible splits of the lost bytes into serial types. LOST_HIGH: the overwritten bytes end
         * in the middle of a 2 byte serial type, its low byte is the first byte still there.
         */
        List<int[]> splits = new ArrayList<>();
        if (lostBytes == 0) {
            splits.add(new int[0]);
        } else if (lostBytes == 1) {
            splits.add(new int[] { LOST_1 });
            splits.add(new int[] { LOST_HIGH });
        } else if (lostBytes == 2) {
            splits.add(new int[] { LOST_1, LOST_1 });
            splits.add(new int[] { LOST_2 });
            splits.add(new int[] { LOST_1, LOST_HIGH });
        } else {
            return;
        }

        int low = page.get(pos + 4) & 0xFF;
        for (int[] split : splits) {
            int lost = split.length;
            if (lost > columns)
                continue;
            boolean high = lost > 0 && split[lost - 1] == LOST_HIGH;
            /* the low byte of a varint has the high bit cleared */
            if (high && (low & 0x80) != 0)
                continue;
            /* read the serial types which are still there */
            int[] p = { pos + 4 + (high ? 1 : 0) };
            long[] serials = new long[columns];
            boolean ok = true;
            for (int i = lost; i < columns && ok; i++) {
                serials[i] = varint(p, end);
                ok = serials[i] >= 0 && typeMatches(i, serials[i]);
            }
            if (!ok)
                continue;
            int hs = p[0] - hp;
            if (hs > 127)
                continue;
            /*
             * with two lost columns of variable size, a NULL in one of them would always fit
             * (the other one takes all the bytes) - NULL is not guessed for them then
             */
            int variableTypes = 0;
            for (int i = 0; i < lost; i++) {
                if (isVariableType(columnType(i)))
                    variableTypes++;
            }
            nullForVariable = variableTypes < 2;
            lowByte = low;
            enumerateLost(pos, hp, hs, serials, split, 0, end, out, new int[2], 0);
            if (budget <= 0)
                return;
        }
    }

    private int lowByte;

    /** Enumerate the lost serial types of the first columns. */
    private void enumerateLost(int pos, int hp, int hs, long[] serials, int[] split, int col, int end, List<Cell> out,
            int[] variable, int nvariable) throws IOException {
        if (budget <= 0)
            return;
        if (col == split.length) {
            resolveVariable(pos, hp, hs, serials, end, out, variable, nvariable);
            return;
        }
        String type = columnType(col);
        if (split[col] == LOST_1) {
            /* fixed size serial types */
            for (long st : new long[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 }) {
                if (st == 0 && !nullForVariable && isVariableType(type))
                    continue;
                if (typeMatches(col, st)) {
                    serials[col] = st;
                    enumerateLost(pos, hp, hs, serials, split, col + 1, end, out, variable, nvariable);
                }
            }
        }
        /* text or blob of unknown length */
        if (isVariableType(type) && nvariable < 2) {
            variable[nvariable] = col * 4 + split[col];
            serials[col] = -1;
            enumerateLost(pos, hp, hs, serials, split, col + 1, end, out, variable, nvariable + 1);
        }
    }

    /** The possible lengths of a lost text/blob column (ascending). */
    private List<Integer> lengths(int code) {
        int col = code / 4, kind = code % 4;
        String type = columnType(col);
        List<Integer> r = new ArrayList<>();
        if (kind == LOST_1) {
            for (int len = 0; len <= MAX_LEN_1; len++)
                r.add(len);
        } else if (kind == LOST_2) {
            for (int len = MAX_LEN_1 + 1; len <= MAX_LEN_2; len++)
                r.add(len);
        } else {
            /* serial type = high * 128 + low byte; its parity tells text (odd) or blob (even) */
            boolean text = lowByte % 2 == 1;
            if ((text && "BLOB".equals(type)) || (!text && "TEXT".equals(type)))
                return r;
            for (int h = 1; h < 128; h++) {
                long st = h * 128L + lowByte;
                int len = (int) ((st - (text ? 13 : 12)) / 2);
                if (len <= MAX_LEN_2)
                    r.add(len);
            }
        }
        return r;
    }

    private long lostSerial(int code, int len) {
        int col = code / 4;
        if (code % 4 == LOST_HIGH) {
            /* the parity of the serial type is given by the low byte */
            return (lowByte % 2 == 1) ? 13 + 2L * len : 12 + 2L * len;
        }
        return textOrBlob(col, len);
    }

    /** Determine the length of the lost text/blob columns. */
    private void resolveVariable(int pos, int hp, int hs, long[] serials, int end, List<Cell> out, int[] variable,
            int nvariable) throws IOException {
        if (nvariable == 0) {
            add(out, buildCell(pos, hp, hs, serials, end));
            return;
        }
        int fixed = 0;
        for (int i = 0; i < columns; i++) {
            if (serials[i] >= 0)
                fixed += serialSize(serials[i]);
        }
        int dataStart = hp + hs;
        int exact = end - dataStart - fixed;
        if (nvariable == 1) {
            int col = variable[0] / 4;
            List<Integer> lengths = lengths(variable[0]);
            java.util.Set<Integer> allowed = new java.util.HashSet<>(lengths);
            /* the length filling the freeblock first, then shorter cells */
            List<Integer> order = new ArrayList<>();
            for (int d = 0; d <= 3; d++) {
                if (allowed.contains(exact - d))
                    order.add(exact - d);
            }
            for (int i = lengths.size() - 1; i >= 0; i--) {
                if (lengths.get(i) <= exact - 4)
                    order.add(lengths.get(i));
            }
            for (int len : order) {
                if (budget <= 0)
                    break;
                serials[col] = lostSerial(variable[0], len);
                add(out, buildCell(pos, hp, hs, serials, end));
            }
            serials[col] = -1;
            return;
        }
        /* two lost columns of unknown length: only the total is known (the cell fills the freeblock) */
        int col1 = variable[0] / 4, col2 = variable[1] / 4;
        java.util.Set<Integer> allowed2 = new java.util.HashSet<>(lengths(variable[1]));
        int best = -1;
        double bestScore = 0, secondScore = 0;
        for (int len1 : lengths(variable[0])) {
            int len2 = exact - len1;
            if (len2 < 0 || !allowed2.contains(len2))
                continue;
            double s = share(col1, len1) + share(col2, len2);
            if (s > bestScore) {
                secondScore = bestScore;
                bestScore = s;
                best = len1;
            } else if (s > secondScore) {
                secondScore = s;
            }
        }
        /* only if the live records of the page show a dominant length */
        if (best >= 0 && bestScore >= 0.5 && bestScore > secondScore) {
            serials[col1] = lostSerial(variable[0], best);
            serials[col2] = lostSerial(variable[1], exact - best);
            add(out, buildCell(pos, hp, hs, serials, end));
        }
        serials[col1] = -1;
        serials[col2] = -1;
    }

    private double share(int col, int len) {
        Map<Integer, Integer> h = textLengths.get(col);
        if (h == null)
            return 0;
        int n = 0;
        for (int v : h.values())
            n += v;
        if (n < 3)
            return 0;
        return h.getOrDefault(len, 0) / (double) n;
    }

    private long textOrBlob(int col, int len) {
        return "BLOB".equals(columnType(col)) ? 12 + 2L * len : 13 + 2L * len;
    }

    private void add(List<Cell> out, Cell c) {
        if (c != null)
            out.add(c);
    }

    /** Build and check a cell with the given header. */
    private Cell buildCell(int pos, int hp, int hs, long[] serials, int end) throws IOException {
        budget--;
        long data = 0;
        for (int i = 0; i < columns; i++) {
            int size = serialSize(serials[i]);
            if (size < 0 || !typeMatches(i, serials[i]))
                return null;
            data += size;
        }
        long payload = hs + data;
        if (payload > Integer.MAX_VALUE)
            return null;
        /* the payload length (and the rowid) must fit into the bytes before the header */
        int plen = varintLength(payload);
        int prefix = hp - pos;
        if (indexPage ? prefix != plen : (prefix - plen < 1 || prefix - plen > 9))
            return null;
        Cell c = new Cell();
        c.start = pos;
        c.serials = serials.clone();
        c.dataStart = hp + hs;
        c.payload = (int) payload;
        c.local = aux.localPayloadSize(c.payload, indexPage);
        if (c.local < c.payload) {
            /* spilled record: the local part is followed by the number of the first overflow page */
            if (c.local < hs || hp + c.local + 4 > end)
                return null;
            c.overflow = page.getInt(hp + c.local);
            if (c.overflow <= 0 || c.overflow > job.numberofpages)
                return null;
            c.end = hp + c.local + 4;
        } else {
            c.end = hp + c.payload;
        }
        if (c.end > end || !valuesValid(c))
            return null;
        return c;
    }

    /** Text values must be valid in the database encoding (checked for the local part). */
    private boolean valuesValid(Cell c) {
        int p = c.dataStart;
        int localEnd = c.local < c.payload ? c.end - 4 : c.end;
        for (int i = 0; i < columns; i++) {
            int size = serialSize(c.serials[i]);
            if (c.serials[i] >= 13 && c.serials[i] % 2 == 1) {
                int len = Math.min(size, localEnd - p);
                if (len > 0 && !validText(p, len))
                    return false;
            }
            p += size;
            if (p >= localEnd)
                break;
        }
        return true;
    }

    private boolean validText(int from, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++)
            b[i] = page.get(from + i);
        CharsetDecoder dec = job.db_encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharSequence s = dec.decode(ByteBuffer.wrap(b));
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == 0 || (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t'))
                    return false;
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Convert a cell into a record. */
    private SqliteInternalRow toRow(Cell c, long pageOffset) throws IOException {
        SqliteInternalRow row = new SqliteInternalRow();
        row.setTableName(td.tblname);
        row.setRecordType(Global.DELETED_RECORD_IN_PAGE);
        row.setOffset(pageOffset + c.start);
        if (c.rowid != Long.MIN_VALUE)
            row.setRowId(c.rowid);

        byte[] header = new byte[0];
        for (long st : c.serials)
            header = concat(header, encodeVarint(st));
        SqliteElement[] elements = Auxiliary.convertHeaderToSqliteElements(header, job.db_encoding);
        if (elements == null || elements.length != columns)
            return null;

        ByteBuffer data;
        if (c.local < c.payload) {
            int hs = c.dataStart - (c.end - 4 - c.local);
            data = aux.spilledPayload(page, c.dataStart, c.local - hs, c.overflow, c.payload - c.local);
        } else {
            byte[] d = new byte[c.end - c.dataStart];
            for (int i = 0; i < d.length; i++)
                d[i] = page.get(c.dataStart + i);
            data = ByteBuffer.wrap(d);
        }
        for (int i = 0; i < columns; i++) {
            SqliteElement en = elements[i];
            if (i == rowidColumn && en.type == SerialTypes.PRIMARY_KEY) {
                if (c.rowid != Long.MIN_VALUE)
                    row.append(new SqliteElementData(en, c.rowid));
                else
                    row.append(new SqliteElementData(null, job.db_encoding));
            } else if (en.type == SerialTypes.INT0) {
                row.append(new SqliteElementData(en, 0));
            } else if (en.type == SerialTypes.INT1) {
                row.append(new SqliteElementData(en, 1));
            } else if (en.length == 0) {
                row.append(new SqliteElementData(null, job.db_encoding));
            } else {
                byte[] value = new byte[Math.min(en.length, data.remaining())];
                data.get(value);
                row.append(new SqliteElementData(en, value));
            }
        }
        return row;
    }

    /* ---------------------------------------------------------------------------- */

    private String columnType(int col) {
        List<String> types = td.getColumntypes();
        return col < types.size() ? types.get(col) : "NUMERIC";
    }

    /** Is the serial type possible for the declared type of the column? */
    private boolean typeMatches(int col, long st) {
        if (st == 10 || st == 11 || st < 0)
            return false;
        if (col == rowidColumn)
            return st == 0;
        if (st == 0 && notNull[col])
            return false;
        boolean integer = st <= 6 || st == 8 || st == 9;
        switch (columnType(col)) {
            case "INT":
                return integer;
            case "TEXT":
                return st == 0 || (st >= 13 && st % 2 == 1);
            case "REAL":
                return integer || st == 7;
            default:
                return true;
        }
    }

    static int serialSize(long st) {
        if (st < 0 || st == 10 || st == 11)
            return -1;
        if (st >= 12)
            return (int) ((st - (st % 2 == 0 ? 12 : 13)) / 2);
        switch ((int) st) {
            case 5:
                return 6;
            case 6:
            case 7:
                return 8;
            case 8:
            case 9:
            case 0:
                return 0;
            default:
                return (int) st;
        }
    }

    static int varintLength(long v) {
        int n = 1;
        while (n < 9 && (v >>> (7 * n)) != 0)
            n++;
        return n;
    }

    static byte[] encodeVarint(long v) {
        int n = varintLength(v);
        byte[] b = new byte[n];
        for (int i = n - 1; i >= 0; i--) {
            b[i] = (byte) ((v & 0x7F) | (i == n - 1 ? 0 : 0x80));
            v >>>= 7;
        }
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** Read a varint at p[0] (not beyond limit), -2 if not possible. */
    private long varint(int[] p, int limit) {
        long v = 0;
        for (int i = 0; i < 9; i++) {
            if (p[0] >= limit || p[0] >= page.limit())
                return -2;
            int b = page.get(p[0]++) & 0xFF;
            if (i == 8)
                return (v << 8) | b;
            v = (v << 7) | (b & 0x7F);
            if ((b & 0x80) == 0)
                return v;
        }
        return v;
    }
}
