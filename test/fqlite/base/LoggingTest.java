package fqlite.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Log messages are written with SLF4J (slf4j-simple writes to System.err in the tests),
 * Base.LOGLEVEL is applied as an additional filter.
 */
public class LoggingTest {

    private PrintStream savedErr;
    private ByteArrayOutputStream captured;
    private int savedLevel;

    @Before
    public void captureStderr() {
        savedErr = System.err;
        savedLevel = Base.LOGLEVEL;
        captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
    }

    @After
    public void restore() {
        System.setErr(savedErr);
        Base.LOGLEVEL = savedLevel;
        Global.LOGLEVEL = Base.ERROR;
    }

    private String output() {
        System.err.flush();
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private static class Component extends Base {
    }

    @Test
    public void messagesGoToSlf4jWithStackTrace() {
        Base.LOGLEVEL = Base.ALL;
        new Component().err("something failed: ", new IllegalStateException("boom"));
        String out = output();
        assertTrue(out, out.contains("ERROR " + Component.class.getName()));
        assertTrue(out, out.contains("something failed: java.lang.IllegalStateException: boom"));
        /* the stack trace is logged too */
        assertTrue(out, out.contains("at fqlite.base.LoggingTest"));
    }

    @Test
    public void loglevelFiltersMessages() {
        Base.LOGLEVEL = Base.NONE;
        Component c = new Component();
        c.err("hidden error");
        c.warning("hidden warning");
        assertEquals("", output());

        Base.LOGLEVEL = Base.ERROR;
        c.warning("hidden warning");
        c.err("visible error");
        String out = output();
        assertFalse(out, out.contains("hidden"));
        assertTrue(out, out.contains("visible error"));
    }

    @Test
    public void anonymousClassesUseTheNameOfTheirSuperClass() {
        Base.LOGLEVEL = Base.ALL;
        new Component() {
        }.err("anonymous");
        assertTrue(output().contains("ERROR " + Component.class.getName() + " - anonymous"));
    }

    /** Failures of Job.run are reported even with LOGLEVEL NONE (they were printed to stderr before). */
    @Test
    public void jobFailuresAreAlwaysReported() {
        Global.LOGLEVEL = Base.NONE;
        Job job = new Job();
        assertEquals(-1, job.run("does-not-exist.db"));
        String out = output();
        assertTrue(out, out.contains("Error while processing does-not-exist.db"));
        assertTrue(out, out.contains("NoSuchFileException"));
    }
}
