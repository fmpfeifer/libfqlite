package fqlite.base;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fqlite.util.Auxiliary;

/**
 * Defines an abstracts Base class with some basic
 * logging functionality.
 *
 * Messages are written with SLF4J (logger named after the concrete class).
 * {@link #LOGLEVEL} is applied as an additional filter before the SLF4J
 * configuration: messages below this level are discarded (e.g. {@link #NONE}
 * discards all of them).
 *
 * @author pawlaszc
 *
 */
public abstract class Base {

	public static final int ALL = 0;
	public static final int DEBUG = 1;
	public static final int INFO = 2;
	public static final int WARNING = 3;
	public static final int ERROR = 4;
	public static final int NONE = 5;
	public static int LOGLEVEL = ALL;

	private transient Logger logger;

	/**
	 * @return the SLF4J logger of this object (named after its class, anonymous
	 *         classes use the name of their super class)
	 */
	protected Logger getLogger() {
		if (logger == null) {
			Class<?> c = getClass();
			while (c.isAnonymousClass() && c.getSuperclass() != null) {
				c = c.getSuperclass();
			}
			logger = LoggerFactory.getLogger(c);
		}
		return logger;
	}

	public void debug(String message) {
	    out(message, DEBUG);
	}

	public void debug(Object... message) {
	    out(message, DEBUG);
    }


    public void info(String message) {
        out(message, INFO);
	}

    public void info(Object... message) {
        out(message, INFO);
    }

	public void warning(String message) {
	    out(message, WARNING);
	}

	public void warning(Object... message) {
        out(message, WARNING);
    }

	public void err(String message) {
	    out(message, ERROR);
	}

	public void err(Object... message) {
        out(message, ERROR);
    }

	private static String objectArrayToMessage(Object[] message) {
	    StringBuilder builder = new StringBuilder(message.length * 8);
	    for (Object o : message) {
	        if (null == o) {
	            builder.append("null");
	        } else {
	            if (o instanceof byte[]) {
	                builder.append(Auxiliary.bytesToHex((byte[]) o));
	            } else if (o instanceof ByteBuffer) {
	                builder.append(Auxiliary.bytesToHex((ByteBuffer) o));
	            } else {
	                builder.append(o.toString());
	            }
	        }
	    }
	    return builder.toString();
	}

	private boolean isEnabled(int level) {
	    if (LOGLEVEL > level) {
	        return false;
	    }
	    Logger log = getLogger();
	    switch (level) {
	        case ERROR:
	            return log.isErrorEnabled();
	        case WARNING:
	            return log.isWarnEnabled();
	        case INFO:
	            return log.isInfoEnabled();
	        default:
	            return log.isDebugEnabled();
	    }
	}

	private void log(int level, String message, Throwable t) {
	    Logger log = getLogger();
	    switch (level) {
	        case ERROR:
	            log.error(message, t);
	            break;
	        case WARNING:
	            log.warn(message, t);
	            break;
	        case INFO:
	            log.info(message, t);
	            break;
	        default:
	            log.debug(message, t);
	    }
	}

	private void out(String message, int level) {
	    if (isEnabled(level)) {
	        log(level, message, null);
	    }
	}

	private void out(Object[] message, int level) {
	    if (isEnabled(level)) {
	        /* a Throwable as last argument is logged with its stack trace */
	        Throwable t = null;
	        if (message.length > 0 && message[message.length - 1] instanceof Throwable) {
	            t = (Throwable) message[message.length - 1];
	        }
	        log(level, objectArrayToMessage(message), t);
        }
    }

	/*
     * The idea behind these method is to only call toString()
     * in messageObject if it is to be logged, otherwise the
     * potentially expensive String generation is bypassed
     */
    public void debug(Object messageObject) {
        if (isEnabled(DEBUG)) {
            debug(String.valueOf(messageObject));
        }
    }

    public void info(Object messageObject) {
        if (isEnabled(INFO)) {
            info(String.valueOf(messageObject));
        }
    }

    public void warning(Object messageObject) {
        if (isEnabled(WARNING)) {
            warning(String.valueOf(messageObject));
        }
    }
}
