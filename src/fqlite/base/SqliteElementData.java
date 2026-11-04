package fqlite.base;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import fqlite.types.SerialTypes;
import fqlite.types.StorageClasses;
import fqlite.util.DatetimeConverter;

public class SqliteElementData {
    private SqliteElement column;
    private byte[] data;
    private Charset charset = StandardCharsets.UTF_8;
    
    public SqliteElementData(SqliteElement column, byte[] data) {
        this.column = column;
        this.data = data;
        this.charset = column.charset;
    }
    
    public SqliteElementData(String data, Charset charset) {
        this.column = null;
        if (null == data) {
            this.data = null;
        } else {
            this.data = data.getBytes(charset);
        }
        this.charset = charset;
    }
    
    public SqliteElementData(long data, Charset charset) {
        this(new SqliteElement(SerialTypes.INT64, StorageClasses.INT, 8, charset), data);
    }
    
    public SqliteElementData(SqliteElement column, long data) {
        this.column = column;
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(data);
        this.data = buffer.array();
        this.charset = column.charset;
    }
    
    public SqliteElementData(double data, Charset charset) {
        this.column = new SqliteElement(SerialTypes.FLOAT64, StorageClasses.FLOAT, 8, charset);
        ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES);
        buffer.putDouble(data);
        this.data = buffer.array();
        this.charset = charset;
    }
    
    public String toString() {
        if (null == column) {
            if (null == data) {
                return "NULL";
            }
            return SqliteElement.decodeString(data, charset).toString();
        }
        return column.toString(data);
    }
    
    public long getIntValue() {
        switch (column.type) {
            case INT0:
                return 0L;
            case INT1:
                return 1L;
            case INT8:
                return SqliteElement.decodeInt8(data[0]);
            case INT16:
                return SqliteElement.decodeInt16(data);
            case INT24:
                return SqliteElement.decodeInt24(data);
            case INT32:
                return SqliteElement.decodeInt32(data);
            case INT48:
                return SqliteElement.decodeInt48(data);
            case INT64:
            case PRIMARY_KEY:
                return SqliteElement.decodeInt64(data);
            default:
        }
        return 0L;
    }
    
    public String getTextValue() {
        if (column == null) {
            if (data != null) {
                return SqliteElement.decodeString(data, charset).toString();
            } else {
                return "";
            }
        }
        switch (column.type) {
            case STRING:
                return SqliteElement.decodeString(data, charset).toString();
            default:
        }
        return null;
    }
    
    public byte[] getBlobValue() {
        return data;
    }
    
    public double getFloatValue() {
        switch (column.type) {
            case FLOAT64:
                return SqliteElement.decodeFloat64(data);
            default:
        }
        return 0.0;
    }
    
    public Object getObject() {
        return getObject(Global.CONVERT_DATETIME);
    }

    /**
     * @param convertDateTime whether values that look like timestamps are converted to a date string
     * @return the decoded value
     */
    Object getObject(boolean convertDateTime) {
        if (data == null) {
            return null;
        }
        if (column == null) {
            return SqliteElement.decodeString(data, charset).toString();
        }
        if (data.length == 0 && column.type != SerialTypes.INT0 && column.type != SerialTypes.INT1) {
            return null;
        }

        switch (column.type) {
            case INT0:
                return 0;
            case INT1:
                return 1;
            case STRING:
                return SqliteElement.decodeString(data, charset).toString();
            case INT8:
                return SqliteElement.decodeInt8(data[0]);
            case INT16:
                return SqliteElement.decodeInt16(data);
            case INT24:
                return SqliteElement.decodeInt24(data);
            case INT32:
                return SqliteElement.decodeInt32(data);
            case INT48:
            case INT64:
                long lValue;
                if (column.type == SerialTypes.INT48) {
                    lValue = SqliteElement.decodeInt48(data);
                } else {
                    lValue = SqliteElement.decodeInt64(data);
                }
                if (convertDateTime) {
                    String strDateTime = DatetimeConverter.isUnixEpoch(lValue);
                    if (null != strDateTime)
                    {
                        return strDateTime;
                    }
                }
                return lValue;
            case FLOAT64:
                double dValue = SqliteElement.decodeFloat64(data);
                if (convertDateTime) {
                    String strDateTime = DatetimeConverter.isMacAbsoluteTime(dValue);
                    if (null != strDateTime) {
                        return strDateTime;
                    }
                }
                return dValue;
            case BLOB:
                return data;
            case PRIMARY_KEY:
                return SqliteElement.decodeInt64(data);
            case NOTUSED1:
            case NOTUSED2:
        }

        return null;
    }
    
}
