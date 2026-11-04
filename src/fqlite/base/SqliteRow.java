package fqlite.base;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public class SqliteRow {
    private Object[] rowData;
    private String tableName;
    private boolean deleted;
    private Map<String, Integer> colIdx = null;
    private Charset charset = StandardCharsets.UTF_8;
    private RecordSource source = RecordSource.DATABASE;
    private long offset = 0L;

    /**
     * @return the file this record was read from
     */
    public RecordSource getSource() {
        return source;
    }

    void setSource(RecordSource source) {
        this.source = source;
    }

    /**
     * @return offset of the record inside the file it was read from (see {@link #getSource()})
     */
    public long getOffset() {
        return offset;
    }

    void setOffset(long offset) {
        this.offset = offset;
    }

    void setColumnNamesMap(Map<String, Integer> colIdx) {
        this.colIdx = colIdx;
    }

    public Set<String> getColumnNames() {
        if (colIdx != null) {
            return colIdx.keySet();
        }
        return null;
    }

    public int getColumnIndex(String colName) {
        if (colIdx != null) {
            if (colIdx.containsKey(colName)) {
                return colIdx.get(colName);
            }
        }
        return -1;
    }
    
    public void setRowData(Object[] rowData) {
        this.rowData = rowData;
    }

    
    public String getTableName() {
        return tableName;
    }
    
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
    
        
    public String toString() {
        StringBuilder builder = new StringBuilder();
        
        boolean semicolon = false;
        
        for (Object elementData : rowData) {
            if (semicolon) {
                builder.append(";");
            }
            if (elementData == null) {
                builder.append("NULL");
            } else {
                builder.append(elementData.toString());
            }
        }        
        return builder.toString();
    }
    
    public long getIntValue(int colIdx) {
        long result = Long.MIN_VALUE;
        if (colIdx >= 0 && colIdx < rowData.length) {
            Object val = rowData[colIdx];
            if (val != null) {
                if (val instanceof Long) {
                    result = (Long) val;
                } else if (val instanceof Integer) {
                    result = (Integer) val;
                }
            }
        }
        return result;
    }

    public long getIntValue(String col) {
        long result = Long.MIN_VALUE; 
        if (colIdx != null && colIdx.containsKey(col)) {
            result = getIntValue(colIdx.get(col));
        }
        return result;
    }
    
    public String getTextValue(int colIdx) {
        String result = null;
        if (colIdx >= 0 && colIdx < rowData.length) {
            Object val = rowData[colIdx];
            if (val != null) {
                if (val instanceof byte[]) {
                    try {
                        result = new String((byte[]) val, charset);
                    } catch (Exception ignore) {
                    }
                } else {
                    result = val.toString();
                }
            }
        }
        return result;
    }

    public String getTextValue(String col) {
        String result = null;
        if (colIdx != null && colIdx.containsKey(col)) {
            result = getTextValue(colIdx.get(col));
        }
        return result;
    }

    
    public double getFloatValue(int colIdx) {
        double result = Double.NaN;
        if (colIdx >= 0 && colIdx < rowData.length) {
            Object val = rowData[colIdx];
            if (val != null) {
                if (val instanceof Double) {
                    result = (Double) val;
                } else if (val instanceof Float) {
                    result = (Float) val;
                }
            }
        }
        return result;
    }
    
    public double getFloatValue(String col) {
        double result = Double.NaN;
        if (colIdx != null && colIdx.containsKey(col)) {
            result = getFloatValue(colIdx.get(col));
        }
        return result;
    }
    
    public byte[] getBlobValue(int colIdx) {
        byte[] result = null;
        if (colIdx >= 0 && colIdx < rowData.length) {
            Object val = rowData[colIdx];
            if (val != null) {
                if (val instanceof byte[]) {
                    result = (byte[]) val;
                } else if (val instanceof String) {
                    result = ((String) val).getBytes(charset);
                }
            }
        }
        return result;
    }

    public byte[] getBlobValue(String col) {
        byte[] result = null;
        if (colIdx != null && colIdx.containsKey(col)) {
            result = getBlobValue(colIdx.get(col));
        }
        return result;
    }

    public boolean isDeletedRow() {
        return deleted;
    }
    
    public void setDeletedRow(boolean deleted) {
        this.deleted = deleted;
    }
    
    public void setCharset(Charset charset) {
        this.charset = charset;
    }

}
