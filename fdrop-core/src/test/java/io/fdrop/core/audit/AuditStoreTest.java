package io.fdrop.core.audit;

import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.Db;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditStoreTest {

    private static final Principal ADMIN = new Principal("a1", "root", null, false, true);
    private static final Principal USER = new Principal("u1", "alice", null, true, false);

    private FdropConfig cfg;
    private Db db;
    private FdropMetrics metrics;
    private AuditStore store;
    private AuditService service;

    @BeforeEach
    void setUp() {
        cfg = TestSupport.config();
        db = new Db(cfg.db);
        metrics = new FdropMetrics(cfg.management.histogramBuckets);
        store = new AuditStore(cfg.audit, db, metrics);
        Audit.setSink(store);
        service = new AuditService(cfg.audit, store, new NoAuditIndex(), metrics, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        service.close();
        store.close();
        db.close();
    }

    private List<AuditStore.Row> all() {
        store.flush();
        return db.query("SELECT seq FROM audit_event ORDER BY seq", rs -> rs.getLong(1)).stream()
                .map(s -> store.bySeq(List.of(s)).getFirst()).toList();
    }

    private double gauge(String name) {
        return metrics.registry().get(name).gauge().value();
    }

    @Test
    void storesEveryEventWithDerivedColumnsInOneChain() {
        Audit.log("upload", "user", "alice", "ip", "10.0.0.5", "protocol", "HTTP", "path", "/home/a.pdf", "size",
                1_400_000);
        Audit.log("login.refused", "user", "bob", "ip", "203.0.113.9", "protocol", "SFTP", "reason", "disabled");
        Audit.log("folder.member", "user", "root", "folder", "Finance", "member", "carol", "outcome", "success");
        List<AuditStore.Row> rows = all();
        assertEquals(3, rows.size());
        AuditStore.Row up = rows.get(0);
        assertEquals("Files", up.group());
        assertEquals("success", up.outcome());
        assertEquals("alice", up.user());
        assertEquals("10.0.0.5", up.ip());
        assertTrue(up.summary().contains("/home/a.pdf"), up.summary());
        assertEquals("Sign-in", rows.get(1).group());
        assertEquals("refused", rows.get(1).outcome());
        assertEquals("Folders and sharing", rows.get(2).group());
        assertEquals(List.of(1L, 2L, 3L), rows.stream().map(AuditStore.Row::nodeSeq).toList());
        assertEquals("", up.prevHash());
        assertEquals(up.hash(), rows.get(1).prevHash());
        assertTrue(up.data().contains("\"path\":\"/home/a.pdf\""));

        List<AuditStore.ChainCheck> checks = store.verify();
        assertEquals(1, checks.size());
        assertTrue(checks.getFirst().intact());
        assertEquals(3, checks.getFirst().checked());
        assertEquals(1.0, gauge("fdrop.audit.chain.intact"));
    }

    @Test
    void anEditedEventBreaksTheChainThere() {
        for (int i = 0; i < 5; i++) {
            Audit.log("download", "user", "alice", "path", "/home/f" + i);
        }
        all();
        db.update("UPDATE audit_event SET data = REPLACE(data, '/home/f2', '/home/other') WHERE node_seq = 3");
        AuditStore.ChainCheck c = store.verify().getFirst();
        assertFalse(c.intact());
        assertEquals(3L, c.brokenAt());
        assertEquals(2, c.checked());
        assertEquals(0.0, gauge("fdrop.audit.chain.intact"));
        assertTrue(all().stream().anyMatch(r -> r.event().equals("audit.chain.broken")));
    }

    @Test
    void anEditedColumnOrARemovedEventIsFoundToo() {
        for (int i = 0; i < 4; i++) {
            Audit.log("login", "user", "alice");
        }
        all();
        db.update("UPDATE audit_event SET user_name = 'mallory' WHERE node_seq = 2");
        assertEquals(2L, store.verify().getFirst().brokenAt());
        db.update("UPDATE audit_event SET user_name = 'alice' WHERE node_seq = 2");
        db.update("DELETE FROM audit_event WHERE node_seq = 3");
        AuditStore.ChainCheck c = store.verify().getFirst();
        assertEquals(3L, c.brokenAt());
        assertTrue(c.reason().contains("missing"), c.reason());
    }

    @Test
    void pruningMovesTheChainStartAndVerificationGoesOn() throws Exception {
        for (int i = 0; i < 4; i++) {
            Audit.log("login", "user", "alice");
        }
        all();
        Thread.sleep(5);
        int deleted = store.prune(System.currentTimeMillis());
        assertEquals(4, deleted);
        Audit.log("logout", "user", "alice");
        List<AuditStore.Row> rows = all();
        assertEquals(List.of("audit.pruned", "logout"), rows.stream().map(AuditStore.Row::event).toList());
        assertEquals(5L, rows.getFirst().nodeSeq());
        assertTrue(store.verify().getFirst().intact());

        // a restarted node continues its chain from the DB
        store.close();
        store = new AuditStore(cfg.audit, db, metrics);
        Audit.setSink(store);
        Audit.log("login", "user", "alice");
        AuditStore.Row last = all().getLast();
        assertEquals(7L, last.nodeSeq());
        assertTrue(store.verify().getFirst().intact());
    }

    @Test
    void onlyAdminsReadTheLogAndLookingIsRecorded() {
        AuditService.Filter none = new AuditService.Filter(null, null, null, null, null, null, null, null);
        assertEquals(403, assertThrows(FdropException.class, () -> service.list(USER, none, null, 50, "10.0.0.1"))
                .status());
        assertThrows(FdropException.class, () -> service.export(USER, none, "csv", "10.0.0.1"));
        assertThrows(FdropException.class, () -> service.verify(USER, "10.0.0.1"));

        for (int i = 0; i < 5; i++) {
            Audit.log("download", "user", "alice", "path", "/home/f" + i);
        }
        store.flush();
        AuditService.Page p = service.list(ADMIN, none, null, 3, "10.0.0.1");
        assertEquals(5, p.total());
        assertEquals(List.of("/home/f4", "/home/f3", "/home/f2"),
                p.events().stream().map(r -> r.summary().replaceAll(".* ", "")).toList());
        assertNotNull(p.nextCursor());
        assertEquals(5L, p.facets().get("group").get("Files"));
        AuditService.Page next = service.list(ADMIN, none, p.nextCursor(), 3, "10.0.0.1");
        assertEquals(2, next.events().size());
        assertNull(next.nextCursor());
        store.flush();
        assertEquals(1, db.queryLong("SELECT COUNT(*) FROM audit_event WHERE event = 'audit.viewed'"));
        // searching needs the index, which this server does not have
        assertEquals(503, assertThrows(FdropException.class, () -> service.list(ADMIN,
                new AuditService.Filter("alice", null, null, null, null, null, null, null), null, 50, "x")).status());
    }

    @Test
    void exportsTheExactLinesOrCsvAndRefusesTooMany() throws Exception {
        Audit.log("upload", "user", "=cmd", "path", "/home/a,b.txt");
        Audit.log("login", "user", "alice");
        store.flush();
        AuditService.Filter none = new AuditService.Filter(null, null, null, null, null, null, null, null);
        String expected = store.page(null, null, null, 10).stream().map(r -> r.data() + "\n")
                .reduce("", String::concat);
        AuditService.Export jsonl = service.export(ADMIN, none, "jsonl", "10.0.0.1");
        assertEquals(2, jsonl.count());
        assertEquals(expected, read(jsonl));

        AuditService.Export csv = service.export(ADMIN, none, "csv", "10.0.0.1");
        String text = read(csv);
        assertTrue(text.startsWith("id,time,node,node_seq,event,group,user,ip,protocol,outcome,summary\r\n"));
        assertTrue(text.contains(",'=cmd,"), text);
        assertTrue(text.contains("\"uploaded /home/a,b.txt"), text);
        store.flush();
        assertEquals(2, db.queryLong("SELECT COUNT(*) FROM audit_event WHERE event = 'audit.exported'"));

        cfg.audit.exportMax = 1;
        FdropException e = assertThrows(FdropException.class, () -> service.export(ADMIN, none, "csv", "x"));
        assertEquals(413, e.status());
    }

    private static String read(AuditService.Export e) throws Exception {
        try (InputStream in = Channels.newInputStream(e.body())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
