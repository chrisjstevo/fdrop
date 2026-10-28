package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.ExpiryRepo;
import io.fdrop.core.db.ExpiryRepo.Settings;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.SearchRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.expiry.Expiry;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.model.Enums.ExpirySource;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Records.FileExpiry;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.UploadRecord;
import io.fdrop.core.service.VirtualPath.Kind;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.util.Hashing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * File expiry: works out the expiry an upload gets from what was given with it, the folder's or home's default
 * and maximum, and the server's ceiling; stores it; lets people change it; and answers whether a file has expired.
 * Deletion is done by {@link ExpirySweeper}. See "File expiry" in spec/file-expiry.md.
 */
public final class ExpiryService {

    /** What a folder or home allows. {@code areaMax} is the folder's or home's own maximum, null when none. */
    public record Policy(boolean allowed, Expiry defaultExpiry, ExpirySource defaultSource, Expiry areaMax,
                         String areaName, boolean home) {
    }

    /** An expiry resolved for one file; {@code expiry} null means the file never expires. */
    public record Resolved(Expiry expiry, ExpirySource source, boolean shortened) {
    }

    /** A file's expiry as the API shows it. */
    public record View(Instant expiresAt, String expiresIn, ExpirySource source) {
        public static View of(FileExpiry e) {
            return new View(Instant.ofEpochMilli(e.expiresAt()), e.expiresIn(), e.source());
        }
    }

    /** What a change asks for: a duration from now, a date, or neither (clear). */
    public record Change(String expiresIn, Instant expiresAt) {
        public static Change clear() {
            return new Change(null, null);
        }
    }

    private final FdropConfig.FileExpiry cfg;
    private final ExpiryRepo repo;
    private final RecordRepo records;
    private final PathResolver resolver;
    private final StorageBackend storage;
    private final FdropMetrics metrics;
    private final Clock clock;
    /** Re-indexes a file whose expiry changed, so search filters it by its new expiry. */
    private final SearchRepo search;
    private final Expiry serverMax;
    private final Expiry homeDefault;
    private final Expiry homeMax;
    private volatile Consumer<FileExpiry> scheduler = e -> { };

    public ExpiryService(FdropConfig cfg, ExpiryRepo repo, RecordRepo records, PathResolver resolver,
                         StorageBackend storage, FdropMetrics metrics, Clock clock, SearchRepo search) {
        this.search = search;
        this.cfg = cfg.expiry;
        this.repo = repo;
        this.records = records;
        this.resolver = resolver;
        this.storage = storage;
        this.metrics = metrics;
        this.clock = clock;
        this.serverMax = configExpiry("expiry.max", this.cfg.max);
        if (serverMax == null || serverMax.never() || serverMax.longerThan(Expiry.parse("100y"), Instant.EPOCH)) {
            throw new IllegalStateException("expiry.max must be set, and at most 100y");
        }
        this.homeDefault = configExpiry("expiry.home.default", this.cfg.home.defaultExpiry);
        this.homeMax = configExpiry("expiry.home.max", this.cfg.home.max);
        Instant now = Instant.EPOCH;
        for (Expiry e : new Expiry[]{homeDefault, homeMax}) {
            if (e != null && e.longerThan(serverMax, now)) {
                throw new IllegalStateException("expiry.home settings must not be longer than expiry.max");
            }
        }
        if (homeDefault != null && homeMax != null && homeDefault.longerThan(homeMax, now)) {
            throw new IllegalStateException("expiry.home.default must not be longer than expiry.home.max");
        }
    }

    private static Expiry configExpiry(String key, String text) {
        if (text == null || text.isBlank() || text.strip().equalsIgnoreCase("null")) {
            return null;
        }
        try {
            Expiry e = Expiry.parse(text);
            return e.never() ? null : e;
        } catch (FdropException e) {
            throw new IllegalStateException(key + ": " + e.getMessage());
        }
    }

    /** Called with each expiry set or changed, so a node can delete it on time without waiting for a sweep. */
    public void setScheduler(Consumer<FileExpiry> scheduler) {
        this.scheduler = scheduler;
    }

    public boolean enabled() {
        return cfg.enabled;
    }

    public Expiry serverMax() {
        return serverMax;
    }

    public Instant now() {
        return clock.instant();
    }

    // ---- control file names ----

    public String controlSuffix() {
        return cfg.sftp.suffix;
    }

    /** True for a name using the reserved SFTP control suffix, which no file may have. */
    public boolean isControlName(String name) {
        String suffix = cfg.sftp.suffix;
        return name != null && suffix != null && !suffix.isEmpty()
                && name.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))
                && name.length() > suffix.length();
    }

    /** {@code /home/a.pdf.fdrop-expires} → {@code /home/a.pdf}. */
    public String controlTarget(String virtual) {
        return virtual.substring(0, virtual.length() - cfg.sftp.suffix.length());
    }

    public void refuseReservedName(String name) {
        if (isControlName(name)) {
            throw FdropException.badRequest("names ending in '" + cfg.sftp.suffix + "' are reserved for SFTP expiry"
                    + " control files").withType("reserved-name");
        }
    }

    // ---- policy ----

    public Policy policy(VirtualPath vp) {
        return switch (vp.kind()) {
            case HOME -> {
                Expiry own = parseStored(repo.homeDefault(vp.quotaOwner()));
                Expiry def = own != null ? own : homeDefault;
                yield new Policy(true, def, ExpirySource.HOME_DEFAULT, homeMax, "Home", true);
            }
            case SHARED -> {
                Settings s = repo.folderSettings(vp.folder().id());
                yield new Policy(true, parseStored(s.defaultExpiry()), ExpirySource.FOLDER_DEFAULT,
                        parseStored(s.maxExpiry()), "this folder", false);
            }
            default -> new Policy(false, null, null, null, null, false);
        };
    }

    private static Expiry parseStored(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Expiry e = Expiry.parse(text);
        return e.never() ? null : e;
    }

    /** The shortest of the area's maximum and the server's, as an expiry, at {@code start}. */
    private Expiry effectiveMax(Policy p, Instant start) {
        return p.areaMax() != null && !p.areaMax().longerThan(serverMax, start) ? p.areaMax() : serverMax;
    }

    private FdropException tooLong(Policy p, Expiry asked, Instant start) {
        return tooLong(p, asked.never() ? null : "'" + asked.canonical() + "' is longer than", start);
    }

    /** {@code asked} is the start of the sentence ("'2 years' is longer than"), or null for never. */
    private FdropException tooLong(Policy p, String asked, Instant start) {
        boolean areaLimits = p.areaMax() != null && !p.areaMax().longerThan(serverMax, start);
        String detail;
        if (asked == null) {
            detail = (p.home() ? "Files in Home" : "Files in this folder") + " must expire within '"
                    + p.areaMax().canonical() + "'";
        } else if (!areaLimits) {
            detail = asked + " the server maximum of '" + serverMax.canonical() + "'";
        } else if (p.home()) {
            detail = asked + " the maximum of '" + p.areaMax().canonical()
                    + "' for files in Home, set by the administrator";
        } else {
            detail = asked + " this folder's maximum of '" + p.areaMax().canonical() + "', set by the folder owner";
        }
        return FdropException.badRequest(detail).withType(Expiry.PROBLEM_TOO_LONG);
    }

    private static FdropException notAllowed() {
        return FdropException.badRequest("Files in forward folders cannot be given an expiry. Use the folder's"
                + " 'After sending' setting to keep sent files for some days").withType(Expiry.PROBLEM_NOT_ALLOWED);
    }

    private static FdropException disabled() {
        return FdropException.badRequest("File expiry is turned off on this server").withType("expiry-disabled");
    }

    /** Records and audits a refusal, then returns it for throwing. */
    private FdropException refused(Principal p, Via via, String input, FdropException e) {
        String reason = switch (e.type() == null ? "" : e.type()) {
            case Expiry.PROBLEM_INVALID -> "syntax";
            case Expiry.PROBLEM_TOO_LONG -> "too_long";
            case Expiry.PROBLEM_CONFLICTING -> "conflicting";
            default -> "not_allowed";
        };
        metrics.expiryRefused(protocol(via), reason);
        Audit.log("file.expiry.refused", "user", p == null ? null : p.username(), "protocol", via,
                "input", input == null ? null : input.length() > 64 ? input.substring(0, 64) : input,
                "problem", e.type());
        return e;
    }

    private static String protocol(Via via) {
        return via == null ? "system" : via.name().toLowerCase(Locale.ROOT);
    }

    /** Two different expiries given with one upload (say, a header and a field): refused and counted. */
    public FdropException conflicting(Principal p, Via via, String a, String b) {
        return refused(p, via, a + " / " + b, FdropException.badRequest("The expiry was given twice with different"
                + " values ('" + a + "' and '" + b + "'); give it once").withType(Expiry.PROBLEM_CONFLICTING));
    }

    /**
     * Checks an expiry given with an upload, before any bytes are accepted. Returns its canonical form, or null when
     * none was given.
     */
    public String validateExplicit(Principal p, VirtualPath vp, String input, Via via) {
        if (input == null) {
            return null;
        }
        try {
            if (!cfg.enabled) {
                throw disabled();
            }
            Expiry e = Expiry.parse(input);
            Policy pol = policy(vp);
            if (!pol.allowed()) {
                throw notAllowed();
            }
            Instant now = now();
            if (e.never() ? pol.areaMax() != null : e.longerThan(effectiveMax(pol, now), now)) {
                throw tooLong(pol, e, now);
            }
            return e.canonical();
        } catch (FdropException e) {
            throw e.type() == null ? e : refused(p, via, input, e);
        }
    }

    /**
     * The expiry a completed file gets. An explicit value was checked when the upload started; if the limits were
     * lowered since, it is shortened to fit rather than failing a finished transfer.
     */
    public Resolved resolve(VirtualPath vp, String explicit, Instant start) {
        Policy pol = policy(vp);
        if (!pol.allowed() || !cfg.enabled) {
            return new Resolved(null, null, false);
        }
        Expiry max = effectiveMax(pol, start);
        if (explicit != null) {
            Expiry e = Expiry.parse(explicit);
            if (e.never()) {
                return pol.areaMax() != null ? new Resolved(max, ExpirySource.UPLOAD, true)
                        : new Resolved(null, ExpirySource.UPLOAD, false);
            }
            return e.longerThan(max, start) ? new Resolved(max, ExpirySource.UPLOAD, true)
                    : new Resolved(e, ExpirySource.UPLOAD, false);
        }
        if (pol.defaultExpiry() != null) {
            return pol.defaultExpiry().longerThan(max, start) ? new Resolved(max, pol.defaultSource(), true)
                    : new Resolved(pol.defaultExpiry(), pol.defaultSource(), false);
        }
        if (pol.areaMax() != null) {
            return new Resolved(max, pol.defaultSource(), false);
        }
        return new Resolved(null, null, false);
    }

    /**
     * Stores the expiry of a file that has just been completed, replacing any its key had. For SFTP uploads with no
     * explicit value, a pending control file value is used.
     */
    public View applyOnCommit(Principal p, VirtualPath vp, String explicit, Via via, UploadRecord rec,
                              Instant completedAt) {
        String key = vp.key().toString();
        if (explicit == null && via == Via.SFTP) {
            long since = completedAt.toEpochMilli() - Durations.parse(cfg.sftp.pendingFor).toMillis();
            Optional<String> pending = repo.pending(p.sub(), key, since);
            if (pending.isPresent()) {
                explicit = pending.get();
                repo.deletePending(p.sub(), key);
            }
        }
        Resolved r = resolve(vp, explicit, completedAt);
        if (r.expiry() == null) {
            repo.delete(key);
            return null;
        }
        Instant at = r.expiry().from(completedAt);
        FileExpiry fe = new FileExpiry(Hashing.newId("exp"), key, ExpiryRepo.parentOf(key),
                vp.kind() == Kind.HOME ? vp.quotaOwner() : null, vp.folder() == null ? null : vp.folder().id(),
                at.toEpochMilli(), r.expiry().canonical(), r.source(), p.sub(), via, completedAt.toEpochMilli(),
                rec == null ? null : rec.id(), 0, null, 0);
        repo.put(fe);
        if (rec != null) {
            records.setExpiresAt(rec.id(), at.toEpochMilli());
        }
        metrics.expirySet(protocol(via), r.source().name().toLowerCase(Locale.ROOT));
        Audit.log("file.expiry.set", "user", p.username(), "protocol", via, "path", vp.virtual(), "expiresAt", at,
                "expiresIn", r.expiry().canonical(), "source", r.source(), "shortened", r.shortened());
        scheduler.accept(fe);
        search.append(SearchRepo.Op.INDEX, fe.storageKey(), null);
        return View.of(fe);
    }

    // ---- reading ----

    public Optional<FileExpiry> find(StoragePath key) {
        return repo.find(key.toString());
    }

    public Map<String, FileExpiry> children(StoragePath dir) {
        return repo.children(dir.toString());
    }

    public boolean expired(FileExpiry e) {
        return e != null && e.expiresAt() <= clock.millis();
    }

    // ---- changes ----

    /** Sets, changes or clears the expiry of an existing file, as {@code PATCH /api/v1/files} does. */
    public View change(Principal p, String path, Change c, Via via, String reason) {
        VirtualPath vp = resolver.resolve(p, path);
        if (vp.isSynthetic() || vp.isAreaRoot()) {
            throw FdropException.badRequest("not a file: " + vp.virtual());
        }
        if (!vp.writable()) {
            throw FdropException.forbidden("you have read-only access to " + vp.parentVirtual());
        }
        String input = c.expiresIn() != null ? c.expiresIn() : c.expiresAt() == null ? "never" : c.expiresAt().toString();
        Policy pol = policy(vp);
        Instant now = now();
        try {
            if (!cfg.enabled) {
                throw disabled();
            }
            if (!pol.allowed()) {
                throw notAllowed();
            }
        } catch (FdropException e) {
            throw refused(p, via, input, e);
        }
        requireLiveFile(vp);
        String key = vp.key().toString();
        Optional<FileExpiry> before = repo.find(key);
        Instant at;
        String text;
        try {
            Expiry max = effectiveMax(pol, now);
            if (c.expiresAt() != null) {
                at = c.expiresAt();
                if (!at.isAfter(now)) {
                    throw FdropException.badRequest("expiresAt must be in the future").withType(Expiry.PROBLEM_INVALID);
                }
                if (at.isAfter(max.from(now))) {
                    throw tooLong(pol, at + " is later than", now);
                }
                text = null;
            } else {
                Expiry e = Expiry.parse(c.expiresIn() == null ? "never" : c.expiresIn());
                if (e.never() ? pol.areaMax() != null : e.longerThan(max, now)) {
                    throw tooLong(pol, e, now);
                }
                at = e.from(now);
                text = e.never() ? null : e.canonical();
            }
        } catch (FdropException e) {
            throw e.type() == null ? e : refused(p, via, input, e);
        }
        Optional<UploadRecord> rec = records.currentFor(key);
        if (at == null) {
            repo.delete(key);
            rec.ifPresent(r -> records.setExpiresAt(r.id(), null));
            search.append(SearchRepo.Op.INDEX, key, null);
            metrics.expirySet(protocol(via), "cleared");
            Audit.log("file.expiry.cleared", "user", p.username(), "protocol", via, "path", vp.virtual(),
                    "from", before.map(b -> Instant.ofEpochMilli(b.expiresAt())).orElse(null));
            return null;
        }
        FileExpiry fe = new FileExpiry(Hashing.newId("exp"), key, ExpiryRepo.parentOf(key),
                vp.kind() == Kind.HOME ? vp.quotaOwner() : null, vp.folder() == null ? null : vp.folder().id(),
                at.toEpochMilli(), text, ExpirySource.CHANGED, p.sub(), via, now.toEpochMilli(),
                rec.map(UploadRecord::id).orElse(null), 0, null, 0);
        repo.put(fe);
        rec.ifPresent(r -> records.setExpiresAt(r.id(), at.toEpochMilli()));
        metrics.expirySet(protocol(via), "changed");
        Audit.log("file.expiry.changed", "user", p.username(), "protocol", via, "path", vp.virtual(),
                "from", before.map(b -> Instant.ofEpochMilli(b.expiresAt())).orElse(null), "to", at,
                "expiresIn", text, "reason", reason);
        scheduler.accept(fe);
        search.append(SearchRepo.Op.INDEX, fe.storageKey(), null);
        return View.of(fe);
    }

    /** The file must exist, be a file and not have expired. */
    private ObjectInfo requireLiveFile(VirtualPath vp) {
        try {
            ObjectInfo o = storage.stat(vp.key()).orElseThrow(() -> FdropException.notFound("no such file: "
                    + vp.virtual()));
            if (o.directory()) {
                throw FdropException.badRequest("folders have no expiry; set it on the files in them");
            }
            if (expired(repo.find(vp.key().toString()).orElse(null))) {
                throw FdropException.notFound("no such file: " + vp.virtual());
            }
            return o;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A file or folder was deleted: its expiries go with it. */
    public void onDeleted(StoragePath key) {
        repo.deleteUnder(key.toString());
    }

    /**
     * A file or folder moved: its expiries follow it, and any that would outlive the destination's maximum are
     * shortened to it. Returns true when something was shortened.
     */
    public boolean onMoved(Principal p, VirtualPath src, VirtualPath dst) {
        String toFolder = dst.folder() == null ? null : dst.folder().id();
        String toUser = dst.kind() == Kind.HOME ? dst.quotaOwner() : null;
        var moved = repo.move(src.key().toString(), dst.key().toString(), toFolder, toUser);
        Policy pol = policy(dst);
        if (!cfg.enabled || pol.areaMax() == null) {
            return false;
        }
        Instant now = now();
        Expiry max = effectiveMax(pol, now);
        long limit = max.from(now).toEpochMilli();
        Map<String, FileExpiry> byKey = new java.util.HashMap<>();
        moved.forEach(m -> byKey.put(m.storageKey(), m));
        boolean shortened = false;
        try (Stream<ObjectInfo> files = filesUnder(dst.key())) {
            for (ObjectInfo o : (Iterable<ObjectInfo>) files::iterator) {
                FileExpiry e = byKey.get(o.path().toString());
                if (e == null || e.expiresAt() > limit) {
                    shorten(p, o.path().toString(), toFolder, toUser, max, now, "move");
                    shortened = true;
                }
            }
        }
        return shortened;
    }

    private Stream<ObjectInfo> filesUnder(StoragePath key) {
        try {
            Optional<ObjectInfo> self = storage.stat(key);
            if (self.isEmpty()) {
                return Stream.empty();
            }
            if (!self.get().directory()) {
                return Stream.of(self.get());
            }
            return storage.list(key, true).filter(o -> !o.directory());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void shorten(Principal p, String key, String folderId, String userSub, Expiry max, Instant now,
                         String reason) {
        long at = max.from(now).toEpochMilli();
        Optional<FileExpiry> before = repo.find(key);
        Optional<UploadRecord> rec = records.currentFor(key);
        FileExpiry fe = new FileExpiry(Hashing.newId("exp"), key, ExpiryRepo.parentOf(key), userSub, folderId, at,
                max.canonical(), ExpirySource.CHANGED, p == null ? null : p.sub(), null, now.toEpochMilli(),
                rec.map(UploadRecord::id).orElse(null), 0, null, 0);
        repo.put(fe);
        rec.ifPresent(r -> records.setExpiresAt(r.id(), at));
        Audit.log("file.expiry.changed", "user", p == null ? null : p.username(), "storageKey", key,
                "from", before.map(b -> Instant.ofEpochMilli(b.expiresAt())).orElse(null),
                "to", Instant.ofEpochMilli(at), "reason", reason);
        scheduler.accept(fe);
        search.append(SearchRepo.Op.INDEX, fe.storageKey(), null);
    }

    // ---- folder and home settings ----

    public Settings folderSettings(Folder f) {
        return repo.folderSettings(f.id());
    }

    /**
     * Changes a shared folder's default and maximum (owner only; checked by the caller). A null argument leaves
     * that setting alone; blank or {@code never} clears it. Returns the number of files shortened.
     */
    public int updateFolder(Principal p, Folder f, String defaultText, String maxText, boolean applyToExisting) {
        if (defaultText == null && maxText == null) {
            return 0;
        }
        if (!cfg.enabled) {
            throw disabled();
        }
        if (f.type() != FolderType.SHARED) {
            throw notAllowed();
        }
        Settings cur = repo.folderSettings(f.id());
        String def = defaultText == null ? cur.defaultExpiry() : settingText(defaultText);
        String max = maxText == null ? cur.maxExpiry() : settingText(maxText);
        Instant now = now();
        Expiry defE = parseStored(def);
        Expiry maxE = parseStored(max);
        for (Expiry e : new Expiry[]{defE, maxE}) {
            if (e != null && e.longerThan(serverMax, now)) {
                throw FdropException.badRequest("'" + e.canonical() + "' is longer than the server maximum of '"
                        + serverMax.canonical() + "'").withType(Expiry.PROBLEM_TOO_LONG);
            }
        }
        if (defE != null && maxE != null && defE.longerThan(maxE, now)) {
            throw FdropException.badRequest("The default expiry '" + defE.canonical() + "' is longer than the maximum '"
                    + maxE.canonical() + "'").withType(Expiry.PROBLEM_TOO_LONG);
        }
        repo.setFolderSettings(f.id(), new Settings(def, max));
        int changed = 0;
        if (applyToExisting && maxE != null) {
            long limit = maxE.from(now).toEpochMilli();
            Map<String, FileExpiry> rows = new java.util.HashMap<>();
            repo.inFolder(f.id()).forEach(r -> rows.put(r.storageKey(), r));
            try (Stream<ObjectInfo> files = filesUnder(StoragePath.of(f.storagePrefix()))) {
                for (ObjectInfo o : (Iterable<ObjectInfo>) files::iterator) {
                    FileExpiry e = rows.get(o.path().toString());
                    if (e == null || e.expiresAt() > limit) {
                        shorten(p, o.path().toString(), f.id(), null, maxE, now, "folderMaximum");
                        changed++;
                    }
                }
            }
        }
        Audit.log("folder.expiry.changed", "user", p.username(), "folder", f.name(), "default", def, "max", max,
                "applyToExisting", applyToExisting, "filesChanged", changed);
        return changed;
    }

    private static String settingText(String text) {
        if (text.isBlank()) {
            return null;
        }
        Expiry e = Expiry.parse(text);
        return e.never() ? null : e.canonical();
    }

    /** The user's own default for files in their home, or null. */
    public String homeDefault(String sub) {
        return repo.homeDefault(sub);
    }

    /** Server-wide home default and maximum, for the UI. */
    public Settings homeServerSettings() {
        return new Settings(homeDefault == null ? null : homeDefault.canonical(),
                homeMax == null ? null : homeMax.canonical());
    }

    /** Sets a user's (or, by an admin, an application account's) home default; blank or never clears it. */
    public String setHomeDefault(Principal actor, String sub, String text) {
        if (!cfg.enabled) {
            throw disabled();
        }
        String def = settingText(text == null ? "" : text);
        Expiry e = parseStored(def);
        Instant now = now();
        if (e != null) {
            Expiry max = homeMax != null && !homeMax.longerThan(serverMax, now) ? homeMax : serverMax;
            if (e.longerThan(max, now)) {
                throw FdropException.badRequest("'" + e.canonical() + "' is longer than the maximum of '"
                        + max.canonical() + "' for files in Home").withType(Expiry.PROBLEM_TOO_LONG);
            }
        }
        repo.setHomeDefault(sub, def);
        Audit.log("folder.expiry.changed", "user", actor.username(), "home", sub, "default", def);
        return def;
    }

    // ---- forward folders ----

    /** A forward folder keeping sent files for {@code keepDays}: the file expires that long after sending. */
    public void setForwardKeep(ForwardJob j, int keepDays, long sentAt) {
        Expiry keep = Expiry.of(keepDays, Expiry.Unit.DAY);
        long at = keep.from(Instant.ofEpochMilli(sentAt)).toEpochMilli();
        FileExpiry fe = new FileExpiry(Hashing.newId("exp"), j.storageKey(), ExpiryRepo.parentOf(j.storageKey()),
                null, j.folderId(), at, keep.canonical(), ExpirySource.FORWARD_KEEP, null, null, sentAt,
                j.uploadRecordId(), 0, null, 0);
        repo.put(fe);
        if (j.uploadRecordId() != null) {
            records.setExpiresAt(j.uploadRecordId(), at);
        }
        metrics.expirySet("system", "forward_keep");
        Audit.log("file.expiry.set", "user", "system", "storageKey", j.storageKey(), "folder", j.folderId(),
                "expiresAt", Instant.ofEpochMilli(at), "expiresIn", keep.canonical(), "source", "FORWARD_KEEP");
        scheduler.accept(fe);
        search.append(SearchRepo.Op.INDEX, fe.storageKey(), null);
    }

    /**
     * A kept forward file is being sent to another destination: its keep period no longer applies, and starts again
     * when the last destination has it.
     */
    public void clearForwardKeep(String storageKey, String uploadRecordId) {
        Optional<FileExpiry> fe = repo.find(storageKey);
        if (fe.isPresent() && fe.get().source() == ExpirySource.FORWARD_KEEP) {
            repo.delete(storageKey);
            if (uploadRecordId != null) {
                records.setExpiresAt(uploadRecordId, null);
            }
        }
    }

    // ---- SFTP control files ----

    /** Writing {@code <name>.fdrop-expires}: changes an existing file's expiry or holds a pending value. */
    public void controlWrite(Principal p, String targetVirtual, String text) {
        VirtualPath vp = resolver.resolve(p, targetVirtual);
        if (vp.isSynthetic() || vp.isAreaRoot()) {
            throw FdropException.badRequest("not a file: " + vp.virtual());
        }
        if (!vp.writable()) {
            throw FdropException.forbidden("you have read-only access to " + vp.parentVirtual());
        }
        Optional<ObjectInfo> existing = liveFile(vp);
        if (existing.isPresent()) {
            change(p, targetVirtual, new Change(text, null), Via.SFTP, "controlFile");
            return;
        }
        String canonical = validateExplicit(p, vp, text, Via.SFTP);
        repo.putPending(p.sub(), vp.key().toString(), canonical, clock.millis());
        Audit.log("file.expiry.pending", "user", p.username(), "protocol", Via.SFTP, "path", vp.virtual(),
                "expiresIn", canonical);
    }

    /** Reading {@code <name>.fdrop-expires}: one line with the expiry, {@code never}, or a pending value. */
    public String controlRead(Principal p, String targetVirtual) {
        VirtualPath vp = resolver.resolve(p, targetVirtual);
        if (vp.isSynthetic() || vp.isAreaRoot()) {
            throw FdropException.notFound("no such file: " + targetVirtual);
        }
        if (liveFile(vp).isPresent()) {
            return repo.find(vp.key().toString())
                    .map(e -> Instant.ofEpochMilli(e.expiresAt()) + (e.expiresIn() == null ? "" : " " + e.expiresIn()))
                    .orElse("never") + "\n";
        }
        long since = clock.millis() - Durations.parse(cfg.sftp.pendingFor).toMillis();
        return repo.pending(p.sub(), vp.key().toString(), since).map(v -> "pending " + v + "\n")
                .orElseThrow(() -> FdropException.notFound("no such file: " + targetVirtual));
    }

    /** True when a control file for {@code targetVirtual} would read back something (for stat). */
    public Optional<String> controlPeek(Principal p, String targetVirtual) {
        try {
            return Optional.of(controlRead(p, targetVirtual));
        } catch (FdropException e) {
            if (e.status() == 404 || e.status() == 400) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Deleting {@code <name>.fdrop-expires}: clears the file's expiry or drops the pending value. */
    public void controlClear(Principal p, String targetVirtual) {
        VirtualPath vp = resolver.resolve(p, targetVirtual);
        if (vp.isSynthetic() || vp.isAreaRoot()) {
            throw FdropException.notFound("no such file: " + targetVirtual);
        }
        if (liveFile(vp).isPresent()) {
            change(p, targetVirtual, Change.clear(), Via.SFTP, "controlFile");
            return;
        }
        if (!repo.deletePending(p.sub(), vp.key().toString())) {
            throw FdropException.notFound("no such file: " + targetVirtual);
        }
    }

    /** A file was renamed into {@code targetVirtual} over SFTP: a pending value for that name now applies. */
    public void applyPendingAfterRename(Principal p, String targetVirtual) {
        VirtualPath vp = resolver.resolve(p, targetVirtual);
        if (vp.key() == null) {
            return;
        }
        long since = clock.millis() - Durations.parse(cfg.sftp.pendingFor).toMillis();
        Optional<String> pending = repo.pending(p.sub(), vp.key().toString(), since);
        if (pending.isPresent()) {
            repo.deletePending(p.sub(), vp.key().toString());
            change(p, targetVirtual, new Change(pending.get(), null), Via.SFTP, "controlFile");
        }
    }

    public int deleteStalePending() {
        return repo.deletePendingBefore(clock.millis() - Durations.parse(cfg.sftp.pendingFor).toMillis());
    }

    private Optional<ObjectInfo> liveFile(VirtualPath vp) {
        try {
            Optional<ObjectInfo> o = storage.stat(vp.key());
            if (o.isEmpty() || o.get().directory() || expired(repo.find(vp.key().toString()).orElse(null))) {
                return Optional.empty();
            }
            return o;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
