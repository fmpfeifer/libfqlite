package fqlite.base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Helpers to create SQLite databases for the tests and run a {@link Job} on them.
 */
public final class TestSupport {

    private TestSupport() {
    }

    public interface SqlWork {
        void run(Connection c) throws SQLException;
    }

    public static Connection open(Path db) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    }

    /** Create a database (rollback journal mode) and run the given statements on it. */
    public static Path createDb(Path dir, String name, SqlWork work) throws Exception {
        Path db = dir.resolve(name);
        try (Connection c = open(db)) {
            work.run(c);
        }
        return db;
    }

    public static void exec(Connection c, String... sqls) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        }
    }

    /**
     * Create a database in WAL mode, run the work and copy the database and its WAL
     * file to {@code outDir} while the connection is still open (so the WAL is not
     * checkpointed and removed).
     *
     * @return path of the copied database; the WAL is at path + "-wal"
     */
    public static Path createWalDb(Path workDir, Path outDir, String name, SqlWork beforeWal, SqlWork inWal)
            throws Exception {
        Path db = workDir.resolve(name);
        Files.createDirectories(outDir);
        try (Connection c = open(db)) {
            if (beforeWal != null) {
                beforeWal.run(c);
            }
            exec(c, "PRAGMA journal_mode=WAL", "PRAGMA wal_autocheckpoint=0");
            inWal.run(c);
            Path copy = outDir.resolve(name);
            Files.copy(db, copy, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(Path.of(db + "-wal"), Path.of(copy + "-wal"), StandardCopyOption.REPLACE_EXISTING);
            return copy;
        }
    }

    public static Job run(Path db) {
        return run(db, null);
    }

    public static Job run(Path db, Path wal) {
        Job job = new Job();
        if (wal != null) {
            job.readWAL = true;
            job.setWALPath(wal.toString());
        }
        int ret = job.run(db.toString());
        if (ret != 0) {
            throw new AssertionError("job.run returned " + ret);
        }
        return job;
    }

    public static List<String> texts(Job job, String table, String column) {
        return job.getRowsForTable(table).stream().map(r -> r.getTextValue(column))
                .filter(s -> s != null).collect(Collectors.toList());
    }

    public static long countDeleted(Job job, String table) {
        return job.getRowsForTable(table).stream().filter(SqliteRow::isDeletedRow).count();
    }

    public static long countRegular(Job job, String table) {
        return job.getRowsForTable(table).stream().filter(r -> !r.isDeletedRow()).count();
    }

    public static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    public static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var s = Files.walk(p)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(x -> x.toFile().delete());
        }
    }
}
