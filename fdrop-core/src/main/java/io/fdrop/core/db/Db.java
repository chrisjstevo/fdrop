package io.fdrop.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.fdrop.core.config.FdropConfig;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The metadata database: a Hikari pool, Flyway migrations, and a few small JDBC helpers. Statements run in the
 * caller's transaction when one is open on this thread (see {@link #inTransaction}).
 */
public final class Db implements AutoCloseable {

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface TxWork<T> {
        T run() throws SQLException;
    }

    private final HikariDataSource ds;
    private final ThreadLocal<Connection> tx = new ThreadLocal<>();

    public Db(FdropConfig.Db cfg) {
        this(cfg, null);
    }

    public Db(FdropConfig.Db cfg, Object meterRegistry) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.url);
        hc.setUsername(cfg.username);
        if (cfg.password != null && !cfg.password.isEmpty()) {
            hc.setPassword(cfg.password);
        }
        DbTls.properties(cfg).forEach(hc::addDataSourceProperty);
        hc.setMaximumPoolSize(cfg.maxPoolSize);
        hc.setPoolName("fdrop-db");
        if (meterRegistry != null) {
            hc.setMetricRegistry(meterRegistry);
        }
        this.ds = new HikariDataSource(hc);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    public DataSource dataSource() {
        return ds;
    }

    public HikariDataSource hikari() {
        return ds;
    }

    public boolean ping() {
        try (Connection c = ds.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public <T> T inTransaction(TxWork<T> work) {
        if (tx.get() != null) {
            try {
                return work.run();
            } catch (SQLException e) {
                throw new DbException(e);
            }
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            tx.set(c);
            try {
                T result = work.run();
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e instanceof RuntimeException re ? re : new DbException((SQLException) e);
            } finally {
                tx.remove();
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public int update(String sql, Object... args) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            }
        });
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = query(sql, mapper, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long queryLong(String sql, Object... args) {
        return queryOne(sql, rs -> rs.getLong(1), args).orElse(0L);
    }

    private interface ConnWork<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T withConnection(ConnWork<T> work) {
        Connection open = tx.get();
        try {
            if (open != null) {
                return work.run(open);
            }
            try (Connection c = ds.getConnection()) {
                return work.run(c);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) {
                ps.setNull(i + 1, Types.NULL);
            } else if (a instanceof Enum<?> e) {
                ps.setString(i + 1, e.name());
            } else if (a instanceof byte[] b) {
                ps.setBytes(i + 1, b);
            } else {
                ps.setObject(i + 1, a);
            }
        }
    }

    /** Reads a nullable BIGINT. */
    public static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    @Override
    public void close() {
        ds.close();
    }

    public static final class DbException extends RuntimeException {
        public DbException(SQLException cause) {
            super(cause.getMessage(), cause);
        }

        public boolean isUniqueViolation() {
            SQLException e = (SQLException) getCause();
            String state = e.getSQLState();
            return "23505".equals(state) || (state != null && state.startsWith("23") && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("unique"));
        }
    }
}
