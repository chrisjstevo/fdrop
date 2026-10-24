package io.fdrop.core.db;

import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.model.Enums.CertMatch;
import io.fdrop.core.model.Enums.CertState;
import io.fdrop.core.model.Records.AppAccount;
import io.fdrop.core.model.Records.AppClientCert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Application accounts' fdrop settings and their client certificates. */
public final class AppAccountRepo {

    private final Db db;

    public AppAccountRepo(Db db) {
        this.db = db;
    }

    private static AppAccount account(ResultSet rs) throws SQLException {
        return new AppAccount(rs.getString("sub"), rs.getString("description"), rs.getString("responsible_sub"),
                methods(rs.getString("allowed_methods")), sources(rs.getString("allowed_sources")),
                Db.getLong(rs, "password_set_at"), rs.getString("created_by"), rs.getLong("created_at"),
                Db.getLong(rs, "last_login_at"));
    }

    private static AppClientCert cert(ResultSet rs) throws SQLException {
        return new AppClientCert(rs.getString("id"), rs.getString("sub"), CertMatch.valueOf(rs.getString("match_type")),
                rs.getString("sha256"), rs.getString("subject"), rs.getString("san"), Db.getLong(rs, "not_after"),
                CertState.valueOf(rs.getString("state")), rs.getLong("created_at"));
    }

    static Set<AppMethod> methods(String text) {
        Set<AppMethod> out = EnumSet.noneOf(AppMethod.class);
        if (text != null) {
            for (String m : text.split(",")) {
                if (!m.isBlank()) {
                    out.add(AppMethod.valueOf(m.trim()));
                }
            }
        }
        return out;
    }

    static List<String> sources(String text) {
        return text == null || text.isBlank() ? List.of()
                : Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String join(Collection<?> items) {
        return items.stream().map(Object::toString).sorted().collect(Collectors.joining(","));
    }

    public Optional<AppAccount> find(String sub) {
        return db.queryOne("SELECT * FROM app_account WHERE sub = ?", AppAccountRepo::account, sub);
    }

    public List<AppAccount> all() {
        return db.query("SELECT a.* FROM app_account a JOIN users u ON u.sub = a.sub ORDER BY u.username",
                AppAccountRepo::account);
    }

    public void insert(AppAccount a) {
        db.update("INSERT INTO app_account (sub, description, responsible_sub, allowed_methods, allowed_sources,"
                        + " password_set_at, created_by, created_at, last_login_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.sub(), a.description(), a.responsibleSub(), join(a.allowedMethods()), join(a.allowedSources()),
                a.passwordSetAt(), a.createdBy(), a.createdAt(), a.lastLoginAt());
    }

    public void update(AppAccount a) {
        db.update("UPDATE app_account SET description = ?, responsible_sub = ?, allowed_methods = ?,"
                        + " allowed_sources = ? WHERE sub = ?", a.description(), a.responsibleSub(),
                join(a.allowedMethods()), join(a.allowedSources()), a.sub());
    }

    public void setPasswordSetAt(String sub, long at) {
        db.update("UPDATE app_account SET password_set_at = ? WHERE sub = ?", at, sub);
    }

    public void touch(String sub) {
        db.update("UPDATE app_account SET last_login_at = ? WHERE sub = ?", System.currentTimeMillis(), sub);
    }

    /** Accounts a deleted person was responsible for pass to the admin who deleted them. */
    public void reassignResponsible(String fromSub, String toSub) {
        db.update("UPDATE app_account SET responsible_sub = ? WHERE responsible_sub = ?", toSub, fromSub);
    }

    public void delete(String sub) {
        db.update("DELETE FROM app_account WHERE sub = ?", sub);
    }

    // ---- client certificates ----

    public List<AppClientCert> certs(String sub) {
        return db.query("SELECT * FROM app_client_cert WHERE sub = ? ORDER BY created_at", AppAccountRepo::cert, sub);
    }

    public Optional<AppClientCert> cert(String sub, String id) {
        return db.queryOne("SELECT * FROM app_client_cert WHERE sub = ? AND id = ?", AppAccountRepo::cert, sub, id);
    }

    public List<AppClientCert> activeCerts() {
        return db.query("SELECT * FROM app_client_cert WHERE state = 'ACTIVE'", AppAccountRepo::cert);
    }

    public List<AppClientCert> activeByFingerprint(String sha256) {
        return db.query("SELECT * FROM app_client_cert WHERE state = 'ACTIVE' AND match_type = 'PINNED'"
                + " AND sha256 = ?", AppAccountRepo::cert, sha256);
    }

    /** Active by-subject registrations matching the certificate's subject DN or any of its SANs. */
    public List<AppClientCert> activeBySubject(String subject, List<String> sans) {
        StringBuilder sql = new StringBuilder("SELECT * FROM app_client_cert WHERE state = 'ACTIVE'"
                + " AND match_type = 'SUBJECT' AND (subject = ?");
        Object[] args = new Object[1 + sans.size()];
        args[0] = subject;
        for (int i = 0; i < sans.size(); i++) {
            sql.append(" OR san = ?");
            args[i + 1] = sans.get(i);
        }
        sql.append(')');
        return db.query(sql.toString(), AppAccountRepo::cert, args);
    }

    public void addCert(AppClientCert c) {
        db.update("INSERT INTO app_client_cert (id, sub, match_type, sha256, subject, san, not_after, state,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", c.id(), c.sub(), c.match(), c.sha256(),
                c.subject(), c.san(), c.notAfter(), c.state(), c.createdAt());
    }

    public boolean setCertState(String sub, String id, CertState state) {
        return db.update("UPDATE app_client_cert SET state = ? WHERE sub = ? AND id = ?", state, sub, id) > 0;
    }
}
