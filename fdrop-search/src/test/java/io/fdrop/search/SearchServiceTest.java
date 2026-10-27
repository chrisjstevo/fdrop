package io.fdrop.search;

import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.search.SearchService;
import io.fdrop.core.search.SearchService.Hit;
import io.fdrop.core.search.SearchService.Segment;
import io.fdrop.core.service.ExpiryService;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.FolderService;
import io.fdrop.storage.local.LocalStorageBackend;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Search end to end through the services, on the local backend with encryption at rest on. */
class SearchServiceTest {

    @TempDir
    Path dir;

    Fdrop app;
    FdropConfig cfg;
    final Principal alice = TestSupport.user("alice");
    final Principal bob = TestSupport.user("bob");
    final Principal carol = TestSupport.user("carol");
    final Principal admin = TestSupport.admin("root");

    private Fdrop start(String isolation) throws Exception {
        if (cfg == null) {
            cfg = TestSupport.config();
            cfg.search.indexDir = dir.resolve("index").toString();
            cfg.search.commitInterval = "0s";
        }
        cfg.search.extract.isolation = isolation;
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("files"));
        app = TestSupport.fdrop(cfg, b);
        for (Principal p : List.of(alice, bob, carol, admin)) {
            app.accounts.ensure(p);
        }
        return app;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
    }

    private LuceneSearchService search() {
        return (LuceneSearchService) app.search;
    }

    private void put(Principal p, String path, byte[] data) throws Exception {
        try (FileService.FileWrite w = app.files.openWrite(p, path, data.length, true, Via.HTTP, "203.0.113.7")) {
            w.write(ByteBuffer.wrap(data));
            w.commit();
        }
    }

    private void put(Principal p, String path, String text) throws Exception {
        put(p, path, text.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> find(Principal p, String q) throws IOException {
        search().indexNow();
        return app.search.search(p, new SearchService.Query(q, "all", null, null, null, null, null, 50)).results()
                .stream().map(Hit::path).sorted().toList();
    }

    private static String plain(List<Segment> s) {
        return s.stream().map(Segment::text).collect(Collectors.joining());
    }

    private static byte[] pdf(String text) throws IOException {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            d.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(d, page)) {
                c.beginText();
                c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                c.newLineAtOffset(72, 700);
                c.showText(text);
                c.endText();
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx(String text) throws IOException {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.createParagraph().createRun().setText(text);
            d.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] xlsx(String text) throws IOException {
        try (XSSFWorkbook w = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            w.createSheet("Q3").createRow(0).createCell(0).setCellValue(text);
            w.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void findsNamesAndContentsOfEveryFormat() throws Exception {
        start("inprocess");
        put(alice, "/home/notes.txt", "remember the zebracorn budget");
        put(alice, "/home/board.pdf", pdf("Quarterly giraffeplan for the board"));
        put(alice, "/home/minutes.docx", docx("The pangolinmeeting decided nothing"));
        put(alice, "/home/forecast.xlsx", xlsx("narwhalrevenue 2026"));
        put(alice, "/home/page.html", "<html><body><p>okapi <b>report</b></p><script>evil()</script></body></html>");
        put(alice, "/home/data.json", "{\"customer\": \"quokkacorp\"}");
        put(alice, "/home/mail.eml", "From: a@example.com\r\nSubject: axolotl offer\r\n\r\nPlease read.\r\n");
        put(alice, "/home/photo.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0});
        put(alice, "/home/nightly-2026-10-26.csv", "id,amount\n1,10\n");

        assertThat(find(alice, "zebracorn")).containsExactly("/home/notes.txt");
        assertThat(find(alice, "giraffeplan")).containsExactly("/home/board.pdf");
        assertThat(find(alice, "pangolinmeeting")).containsExactly("/home/minutes.docx");
        assertThat(find(alice, "narwhalrevenue")).containsExactly("/home/forecast.xlsx");
        assertThat(find(alice, "okapi")).containsExactly("/home/page.html");
        assertThat(find(alice, "quokkacorp")).containsExactly("/home/data.json");
        assertThat(find(alice, "axolotl")).containsExactly("/home/mail.eml");
        // names split on punctuation and digits; prefixes; type filter; phrases; exclusion
        assertThat(find(alice, "nightly")).containsExactly("/home/nightly-2026-10-26.csv");
        assertThat(find(alice, "2026")).contains("/home/nightly-2026-10-26.csv", "/home/forecast.xlsx");
        assertThat(find(alice, "giraffe*")).containsExactly("/home/board.pdf");
        assertThat(find(alice, "type:pdf board")).containsExactly("/home/board.pdf");
        assertThat(find(alice, "\"the board\"")).containsExactly("/home/board.pdf");
        assertThat(find(alice, "2026 -nightly")).containsExactly("/home/forecast.xlsx");
        assertThat(find(alice, "photo")).containsExactly("/home/photo.png");

        Hit h = app.search.search(alice, new SearchService.Query("zebracorn", "all", null, null, null, null, null,
                20)).results().get(0);
        assertThat(h.location()).isEqualTo("Home");
        assertThat(h.contents()).isEqualTo("ok");
        assertThat(h.snippets().get(0)).contains(new Segment("zebracorn", true));
        Hit png = app.search.search(alice, new SearchService.Query("photo", "all", null, null, null, null, null, 20))
                .results().get(0);
        assertThat(png.contents()).isEqualTo("unsupported");
        // a document's markup comes back as plain text segments, never as HTML
        Hit html = app.search.search(alice, new SearchService.Query("okapi", "all", null, null, null, null, null, 20))
                .results().get(0);
        assertThat(plain(html.snippets().get(0))).doesNotContain("<");
    }

    @Test
    void nobodyFindsWhatTheyCannotOpen() throws Exception {
        start("inprocess");
        put(alice, "/home/secret.txt", "alicesecret payroll");
        FolderService.FolderView team = app.folderService.create(alice, FolderType.SHARED, "Team", null, null);
        app.folderService.putMember(alice, team.id(), "bob", Permission.READ);
        app.files.mkdir(alice, "/shared/Team/2026");
        put(alice, "/shared/Team/2026/plan.txt", "teamplan milestones");
        put(bob, "/home/bobs.txt", "bobsecret");

        assertThat(find(alice, "alicesecret")).containsExactly("/home/secret.txt");
        assertThat(find(bob, "alicesecret")).isEmpty();
        assertThat(find(bob, "teamplan")).containsExactly("/shared/Team/2026/plan.txt");
        assertThat(find(carol, "teamplan")).isEmpty();
        assertThat(find(alice, "bobsecret")).isEmpty();
        // field names, ids and areas are not a way in
        for (String crafted : List.of("area:home:sub-alice", "id:users*", "home:sub-alice", "sub-alice",
                "alicesecret | teamplan", "\"alicesecret\"", "alicese*")) {
            assertThat(find(carol, crafted)).as(crafted).isEmpty();
            assertThat(find(bob, crafted)).as(crafted).doesNotContain("/home/secret.txt");
        }
        assertThat(app.search.suggest(bob, "alicese")).isEmpty();
        assertThat(app.search.suggest(bob, "pla")).extracting(SearchService.Suggestion::path)
                .containsExactly("/shared/Team/2026/plan.txt");
        Hit plan = app.search.search(bob, new SearchService.Query("teamplan", "all", null, null, null, null, null, 20))
                .results().get(0);
        assertThat(plan.location()).isEqualTo("Shared: Team / 2026");

        // membership is read per search: removing bob hides the folder at once
        app.folderService.removeMember(alice, team.id(), "bob");
        assertThat(find(bob, "teamplan")).isEmpty();
        // folder scope
        assertThat(app.search.search(alice, new SearchService.Query("teamplan", "folder", "/shared/Team", null, null,
                null, null, 20)).results()).hasSize(1);
        assertThat(app.search.search(alice, new SearchService.Query("teamplan", "folder", "/home", null, null, null,
                null, 20)).results()).isEmpty();
        assertThatThrownBy(() -> app.search.search(carol, new SearchService.Query("teamplan", "folder",
                "/shared/Team", null, null, null, null, 20))).isInstanceOfSatisfying(FdropException.class,
                e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void movesRenamesDeletesAndExpiryKeepTheIndexTrue() throws Exception {
        start("inprocess");
        FolderService.FolderView f = app.folderService.create(alice, FolderType.SHARED, "Old name", null, null);
        put(alice, "/home/draft.txt", "wombatbudget figures");
        assertThat(find(alice, "wombatbudget")).containsExactly("/home/draft.txt");

        app.files.move(alice, "/home/draft.txt", "/shared/Old name/final.txt");
        assertThat(find(alice, "wombatbudget")).containsExactly("/shared/Old name/final.txt");
        assertThat(find(alice, "final")).containsExactly("/shared/Old name/final.txt");
        assertThat(find(alice, "draft")).isEmpty();

        // a folder rename re-indexes nothing: the location comes from the folder's current name
        app.folderService.update(alice, f.id(), "New name", null, null);
        assertThat(find(alice, "wombatbudget")).containsExactly("/shared/New name/final.txt");

        app.expiry.change(alice, "/shared/New name/final.txt", new ExpiryService.Change("1 sec", null), Via.HTTP,
                "user");
        Thread.sleep(1100);
        assertThat(find(alice, "wombatbudget")).isEmpty();

        put(alice, "/home/gone.txt", "quaggarecord");
        assertThat(find(alice, "quaggarecord")).hasSize(1);
        app.files.delete(alice, "/home/gone.txt", false);
        assertThat(find(alice, "quaggarecord")).isEmpty();
    }

    @Test
    void refusesUnsafeOrOversizeQueriesAndLimitsRate() throws Exception {
        cfg = TestSupport.config();
        cfg.search.indexDir = dir.resolve("index").toString();
        cfg.search.rateLimitPerMinute = 7;
        start("inprocess");
        for (String bad : List.of("x".repeat(501), "a*", "*", String.join(" ", java.util.Collections.nCopies(33,
                "w")), "type:movies report", "")) {
            assertThatThrownBy(() -> app.search.search(alice, new SearchService.Query(bad, "all", null, null, null,
                    null, null, 20))).as(bad.length() > 20 ? bad.substring(0, 20) : bad)
                    .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.type()).isEqualTo("invalid-search"));
        }
        // refused queries count against the limit too: this is the 7th, the 8th is one too many
        app.search.search(alice, new SearchService.Query("report", "all", null, null, null, null, null, 20));
        assertThatThrownBy(() -> app.search.search(alice, new SearchService.Query("report", "all", null, null, null,
                null, null, 20))).isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status())
                .isEqualTo(429));
    }

    @Test
    void resumesAfterARestartAndKeepsTheIndexEncrypted() throws Exception {
        start("inprocess");
        put(alice, "/home/one.txt", "ibexsighting near the lake");
        assertThat(find(alice, "ibexsighting")).hasSize(1);
        app.close();
        app = null;

        start("inprocess");
        put(alice, "/home/two.txt", "lynxsighting in the woods");
        assertThat(find(alice, "ibexsighting")).containsExactly("/home/one.txt");
        assertThat(find(alice, "lynxsighting")).containsExactly("/home/two.txt");
        try (Stream<Path> files = Files.walk(dir.resolve("index"))) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String raw = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                assertThat(raw).as(p.toString()).doesNotContain("ibexsighting").doesNotContain("lynxsighting");
            }
        }

        SearchService.Status st = app.search.status(admin);
        assertThat(st.documents()).isEqualTo(2);
        assertThat(st.appliedSeq()).isEqualTo(st.latestSeq());
        app.search.rebuild(admin);
        search().indexNow();
        assertThat(find(alice, "lynxsighting")).containsExactly("/home/two.txt");
        assertThatThrownBy(() -> app.search.status(alice)).isInstanceOf(FdropException.class);
    }

    @Test
    void findsWordsDeepInLargeTextFiles() throws Exception {
        start("inprocess");
        String filler = "lorem ipsum dolor sit amet ".repeat(3 * 1024 * 1024 / 27);
        put(alice, "/home/large.txt", filler + "deepcanyonword at the end");
        assertThat(find(alice, "deepcanyonword")).containsExactly("/home/large.txt");
        Hit h = app.search.search(alice, new SearchService.Query("deepcanyonword", "all", null, null, null, null, null,
                20)).results().get(0);
        assertThat(h.snippets()).isNotEmpty();
    }

    @Test
    void extractsInASeparateProcess() throws Exception {
        start("fork");
        put(alice, "/home/forked.txt", "tapirexpedition notes");
        put(alice, "/home/forked.pdf", pdf("Okavango tapirjournal"));
        assertThat(find(alice, "tapirexpedition")).containsExactly("/home/forked.txt");
        assertThat(find(alice, "tapirjournal")).containsExactly("/home/forked.pdf");
    }
}
