package fqlite.base;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RollbackJournalTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final int ROWS = 60;

    private static String value(int i) {
        return "journalrow-" + i + "-" + TestSupport.repeat('y', 80);
    }

    private static Set<String> expected() {
        Set<String> s = new HashSet<>();
        for (int i = 0; i < ROWS; i++) {
            s.add(value(i));
        }
        return s;
    }

    private static Set<String> journalValues(Job job) {
        return job.getRowsForTable("t").stream().filter(r -> r.getSource() == RecordSource.ROLLBACK_JOURNAL)
                .map(r -> r.getTextValue("v")).filter(v -> v != null).collect(Collectors.toSet());
    }

    private static Job runWithJournal(Path db) {
        Job job = new Job();
        job.readRollbackJournal = true;
        job.setRollbackJournalPath(db + "-journal");
        if (job.run(db.toString()) != 0) {
            throw new AssertionError("job failed");
        }
        return job;
    }

    private Path createAndFill(String mode) throws Exception {
        Path work = tmp.newFolder().toPath();
        Path db = work.resolve("j.db");
        try (Connection c = TestSupport.open(db)) {
            TestSupport.exec(c, "PRAGMA journal_mode=" + mode, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)");
            c.setAutoCommit(false);
            for (int i = 0; i < ROWS; i++) {
                TestSupport.exec(c, "INSERT INTO t(v) VALUES('" + value(i) + "')");
            }
            c.commit();
            c.setAutoCommit(true);
        }
        return db;
    }

    /** A journal copied during a transaction holds the original content of the changed pages. */
    @Test
    public void hotJournal() throws Exception {
        Path db = createAndFill("DELETE");
        Path out = tmp.newFolder().toPath().resolve("j.db");
        try (Connection c = TestSupport.open(db)) {
            /* a tiny page cache: the changed pages are written to the database file before the commit */
            TestSupport.exec(c, "PRAGMA cache_size=1");
            c.setAutoCommit(false);
            TestSupport.exec(c, "UPDATE t SET v = 'changed'");
            Files.copy(db, out, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(Path.of(db + "-journal"), Path.of(out + "-journal"), StandardCopyOption.REPLACE_EXISTING);
            c.rollback();
        }
        Job job = runWithJournal(out);
        /* pages written to the database file: the original values only exist in the journal (deleted) */
        Set<String> journal = journalValues(job);
        assertTrue(!journal.isEmpty());
        assertTrue(job.getRowsForTable("t").stream().filter(r -> r.getSource() == RecordSource.ROLLBACK_JOURNAL)
                .allMatch(SqliteRow::isDeletedRow));
        /* pages still unchanged in the database file: the values are current records */
        Set<String> all = new HashSet<>(journal);
        job.getRowsForTable("t").stream().filter(r -> !r.isDeletedRow()).map(r -> r.getTextValue("v")).forEach(all::add);
        assertTrue(all.containsAll(expected()));
    }

    /** Journal pages identical to the database pages are not reported twice. */
    @Test
    public void unchangedJournalPagesAreNotReportedTwice() throws Exception {
        Path db = createAndFill("DELETE");
        Path out = tmp.newFolder().toPath().resolve("j.db");
        try (Connection c = TestSupport.open(db)) {
            c.setAutoCommit(false);
            /* the change stays in the page cache, the database file is unchanged */
            TestSupport.exec(c, "UPDATE t SET v = 'changed'");
            Files.copy(db, out, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(Path.of(db + "-journal"), Path.of(out + "-journal"), StandardCopyOption.REPLACE_EXISTING);
            c.rollback();
        }
        Job job = runWithJournal(out);
        assertTrue(journalValues(job).isEmpty());
        assertTrue(job.getRowsForTable("t").stream().filter(r -> !r.isDeletedRow()).count() == ROWS);
    }

    /** In PERSIST mode the journal header is zeroed after the commit, but the pages are still there. */
    @Test
    public void persistedJournalWithZeroedHeader() throws Exception {
        Path db = createAndFill("PERSIST");
        try (Connection c = TestSupport.open(db)) {
            TestSupport.exec(c, "PRAGMA journal_mode=PERSIST", "UPDATE t SET v = 'changed'");
        }
        assertTrue(Files.size(Path.of(db + "-journal")) > 512);
        Job job = runWithJournal(db);
        assertTrue(journalValues(job).containsAll(expected()));
    }
}
