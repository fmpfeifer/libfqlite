package fqlite.base;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.sql.Date;
import java.text.SimpleDateFormat;
import fqlite.types.SerialTypes;
import fqlite.types.StorageClasses;
import fqlite.util.Auxiliary;
import fqlite.util.DatetimeConverter;


/**
 * This class represents a concrete Table column. 
 * 
 * @author pawlaszc
 *
 */
public class SqliteElement {
	public SerialTypes type;
	public StorageClasses serial;
	public int length;
	public Charset charset;

	public SqliteElement(SerialTypes type, StorageClasses serial, int length, Charset charset) {
		this.length = length;
		this.type = type;
		this.serial = serial;
		this.charset = charset;
	}

	public final String toString(byte[] value) {

	    if (value.length == 0 && type != SerialTypes.INT0 && type != SerialTypes.INT1) {
	        return "";
	    }

	    switch (type) {
	        case INT0:
	            return "0";
	        case INT1:
	            return "1";
	        case STRING:
	            return decodeString(value, charset).toString();
	        case INT8:
	            return String.valueOf(decodeInt8(value[0]));
	        case INT16:
	            return String.valueOf(decodeInt16(value));
	        case INT24:
	            return String.valueOf(decodeInt24(value));
	        case INT32:
	            return String.valueOf(decodeInt32(value));
	        case INT48:
	        case INT64:
	            long lValue;
	            if (type == SerialTypes.INT48) {
	                lValue = decodeInt48(value);
	            } else {
	                lValue = decodeInt64(value);
	            }
	            if (Global.CONVERT_DATETIME) {
	                String strDateTime = DatetimeConverter.isUnixEpoch(lValue);
	                if (null != strDateTime)
	                {
	                    return strDateTime;
	                }
	            }
	            return String.valueOf(lValue);
	        case FLOAT64:
	            double dValue = decodeFloat64(value);
	            if (Global.CONVERT_DATETIME) {
	                String strDateTime = DatetimeConverter.isMacAbsoluteTime(dValue);
	                if (null != strDateTime) {
	                    return strDateTime;
	                }
	            }
	            /* independent of the default locale of the machine (decimal point) */
	            return String.format(java.util.Locale.ROOT, "%.8f", dValue);
	        case BLOB:
	            return String.valueOf(Auxiliary.bytesToHex(value));
	        case PRIMARY_KEY:
	            return String.valueOf(decodeInt64(value));
            case NOTUSED1:
            case NOTUSED2:
	    }

		return null;

	}

	public final static int decodeInt8(byte v) {
		return v;
	}

	final static int decodeInt16(byte[] v) {
	    if (v.length < 2)
	        return 0;
		ByteBuffer bf = ByteBuffer.wrap(v);
		return bf.getShort();
	}

	final static int decodeInt24(byte[] v) {
		int result = int24bytesToUInt(v);
		/* 24-bit twos-complement integer -> extend the sign */
		if (v.length >= 3)
			result = (result << 8) >> 8;
		return result;
	}

	private static int int24bytesToUInt(byte[] input) {
	    if (input.length == 0)
	        return 0;
		if (input.length == 1)
            return (0 & 0xFF << 24) | (0 & 0xFF) << 16 | (0 & 0xFF) << 8 | (input[0] & 0xFF) << 0;
		if (input.length == 2)
            return (0 & 0xFF << 24) | (0 & 0xFF) << 16 | (input[0] & 0xFF) << 8 | (input[1] & 0xFF) << 0;

		return (0 & 0xFF << 24) | (input[0] & 0xFF) << 16 | (input[1] & 0xFF) << 8 | (input[2] & 0xFF) << 0;
	}

	final static int decodeInt32(byte[] v) {
	    if (v.length < 4) {
	        return 0;
	    }
		ByteBuffer bf = ByteBuffer.wrap(v);
		return bf.getInt();
	}

	final static long decodeInt48(byte[] v) {
		// we have to read 6 Bytes
		if (v.length < 6)
			return 0;
		ByteBuffer bf = ByteBuffer.wrap(v);
		byte[] value = bf.array();
		byte[] converted = new byte[8];

		for (int i = 0; i < 6; i++) {
			converted[i + 2] = value[i];
		}
		ByteBuffer result = ByteBuffer.wrap(converted);

		/* 48-bit twos-complement integer -> extend the sign */
		return (result.getLong() << 16) >> 16;
	}

	final static long decodeInt64(byte[] v) {
	    if (v.length < 8)
	        return 0;
		ByteBuffer bf = ByteBuffer.wrap(v);
		return bf.getLong();
	}

	final static String convertToDate(long value) {
		Date d = new Date(value / 1000);
		SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy hh:mm:ss");
		return dateFormat.format(d);
	}

	final static double decodeFloat64(byte[] v) {
	    if (v.length < 8)
	        return Double.NaN;
		ByteBuffer bf = ByteBuffer.wrap(v);
		
		return bf.getDouble();
	}

	final static CharBuffer decodeString(byte[] v, Charset charset) {
		return charset.decode(ByteBuffer.wrap(v));
	}

	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

	public static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = HEX_ARRAY[v >>> 4];
			hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
		}
		return new String(hexChars);
	}

	public static boolean isStringContent(byte[] value) {
		float threshold = 0.8f;
		int printable = 0;

		for (byte b : value) {
			if (b >= 32 && b < 127) {
				printable++;
			}
		}

		if (value.length > 0 && (float) printable / value.length > threshold) {
			return true;
		}

		return false;
	}

}
