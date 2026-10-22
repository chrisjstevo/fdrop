package io.fdrop.sftp;

import io.fdrop.core.Fdrop;
import io.fdrop.core.audit.Audit;
import io.fdrop.core.config.Durations;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.FolderForward;
import io.fdrop.core.model.Records.ForwardClientKey;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.storage.StoragePath;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends files that land in forward folders to their destination SFTP servers. Each file goes to
 * {@code .<name>.fdrop-part}, its size is checked, and only then is it renamed to its final name, so the receiver
 * never sees a partial file. Jobs for one folder go in arrival order; at most {@code perDestinationConcurrency}
 * run per destination and {@code workers} in total. Connection and I/O errors retry with backoff (1 min doubling to
 * 1 h) for {@code retryFor}; host-key and login failures fail at once.
 */
public final class ForwardWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ForwardWorker.class);
    private static final long MIN_BACKOFF = Duration.ofMinutes(1).toMillis();
    private static final long MAX_BACKOFF = Duration.ofHours(1).toMillis();

    private final Fdrop app;
    private final ForwardClient client;
    private final Semaphore workers;
    private final int perDestination;
    private final long retryFor;
    private final Map<String, AtomicInteger> runningPerDestination = new ConcurrentHashMap<>();
    private final Set<String> runningFolders = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("forward-poller").daemon().factory());
    private volatile boolean running = true;
    /** Test hook: backoff override in milliseconds, or -1. */
    volatile long backoffOverride = -1;

    public ForwardWorker(Fdrop app, ForwardClient client) {
        this.app = app;
        this.client = client;
        this.workers = new Semaphore(Math.max(1, app.config.forward.workers));
        this.perDestination = Math.max(1, app.config.forward.perDestinationConcurrency);
        this.retryFor = Durations.parse(app.config.forward.retryFor).toMillis();
    }

    public ForwardWorker start() {
        app.recorder.setJobListener(this::wake);
        poller.scheduleWithFixedDelay(this::poll, 1, 5, TimeUnit.SECONDS);
        return this;
    }

    /** Called when a job is queued, to start it without waiting for the next poll. */
    public void wake() {
        poller.execute(this::poll);
    }

    void poll() {
        if (!running) {
            return;
        }
        try {
            for (ForwardJob j : app.records.dueJobs(System.currentTimeMillis(), 200)) {
                if (runningFolders.contains(j.folderId())) {
                    continue;
                }
                AtomicInteger perDest = runningPerDestination.computeIfAbsent(j.destinationId(),
                        k -> new AtomicInteger());
                if (perDest.get() >= perDestination || !workers.tryAcquire()) {
                    continue;
                }
                if (!app.records.claim(j.id(), j.state())) {
                    workers.release();
                    continue;
                }
                perDest.incrementAndGet();
                runningFolders.add(j.folderId());
                Thread.ofVirtual().name("forward-" + j.id()).start(() -> {
                    try {
                        process(j);
                    } finally {
                        perDest.decrementAndGet();
                        runningFolders.remove(j.folderId());
                        workers.release();
                        wake();
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("forward poll failed: {}", e.toString());
        }
    }

    /** Runs one job to its next state. Package-private for tests. */
    void process(ForwardJob j) {
        long start = System.nanoTime();
        Optional<Folder> folder = app.folders.find(j.folderId());
        Optional<ForwardDestination> dest = app.destinationService.destination(j.destinationId());
        if (folder.isEmpty() || dest.isEmpty()) {
            fail(j, "folder or destination no longer exists", "io", true);
            return;
        }
        ForwardDestination d = dest.get();
        if (!d.enabled() || !app.destinations.isAssigned(d.id(), folder.get().ownerSub())) {
            app.records.updateJob(j.id(), JobState.PAUSED, "destination disabled or no longer assigned", 0,
                    j.firstFailedAt(), null);
            return;
        }
        Optional<ForwardClientKey> key = app.destinationService.usableKey(d.id());
        if (key.isEmpty() || app.destinationService.secrets() == null) {
            fail(j, "destination has no active client key", "auth_rejected", true);
            return;
        }
        try {
            app.hostPolicy.check(d.host());
        } catch (RuntimeException e) {
            fail(j, e.getMessage(), "connect", true);
            return;
        }
        try {
            KeyPair kp = MinaSshTools.loadPrivateKey(app.destinationService.secrets().open(key.get().privateKeyEnc()));
            send(j, d, kp, key.get().userCert());
            long now = System.currentTimeMillis();
            app.records.updateJob(j.id(), JobState.SENT, null, 0, j.firstFailedAt(), now);
            app.metrics.forwardSend(d.id(), true, System.nanoTime() - start);
            app.metrics.forwardBytes(d.id(), j.size());
            Audit.log("forward", "folder", folder.get().name(), "file", j.fileName(), "bytes", j.size(),
                    "destination", d.name(), "host", d.host(), "outcome", "sent");
            afterSent(folder.get(), j);
        } catch (ForwardClient.ForwardException e) {
            app.metrics.forwardSend(d.id(), false, System.nanoTime() - start);
            fail(j, e.getMessage(), reason(e.failure()), e.permanent());
            if (e.permanent()) {
                log.warn("forward of {} to {} failed permanently: {}", j.fileName(), d.host(), e.getMessage());
            }
        } catch (ConflictException e) {
            app.metrics.forwardSend(d.id(), false, System.nanoTime() - start);
            fail(j, e.getMessage(), "conflict", true);
        } catch (IOException | RuntimeException e) {
            app.metrics.forwardSend(d.id(), false, System.nanoTime() - start);
            fail(j, e.getMessage() == null ? e.toString() : e.getMessage(), "io", false);
        }
    }

    private static String reason(ForwardClient.Failure f) {
        return switch (f) {
            case HOST_KEY_MISMATCH -> "host_key_mismatch";
            case AUTH_REJECTED -> "auth_rejected";
            case CONNECT -> "connect";
            case IO -> "io";
        };
    }

    private static final class ConflictException extends IOException {
        ConflictException(String m) {
            super(m);
        }
    }

    private void send(ForwardJob j, ForwardDestination d, KeyPair kp, String cert) throws IOException {
        try (ClientSession session = client.open(d, kp, cert);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            String dir = d.remoteDir().endsWith("/") && d.remoteDir().length() > 1
                    ? d.remoteDir().substring(0, d.remoteDir().length() - 1) : d.remoteDir();
            mkdirs(sftp, dir);
            String finalPath = dir + "/" + j.fileName();
            String tempPath = dir + "/." + j.fileName() + ".fdrop-part";
            boolean exists = exists(sftp, finalPath);
            if (exists && !d.overwrite()) {
                throw new ConflictException(finalPath + " already exists on the destination");
            }
            try (ReadableByteChannel ch = app.storage.openRead(StoragePath.of(j.storageKey()), 0, -1);
                 InputStream in = Channels.newInputStream(ch);
                 OutputStream out = sftp.write(tempPath)) {
                in.transferTo(out);
            }
            long remoteSize = sftp.stat(tempPath).getSize();
            if (remoteSize != j.size()) {
                safeRemove(sftp, tempPath);
                throw new IOException("destination has " + remoteSize + " bytes, expected " + j.size());
            }
            if (exists) {
                sftp.remove(finalPath);
            }
            sftp.rename(tempPath, finalPath);
        }
    }

    private static void mkdirs(SftpClient sftp, String dir) throws IOException {
        if (dir.isEmpty() || dir.equals("/") || dir.equals(".") || exists(sftp, dir)) {
            return;
        }
        int slash = dir.lastIndexOf('/');
        if (slash > 0) {
            mkdirs(sftp, dir.substring(0, slash));
        }
        try {
            sftp.mkdir(dir);
        } catch (SftpException e) {
            if (!exists(sftp, dir)) {
                throw e;
            }
        }
    }

    private static boolean exists(SftpClient sftp, String path) throws IOException {
        try {
            sftp.stat(path);
            return true;
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) {
                return false;
            }
            throw e;
        }
    }

    private static void safeRemove(SftpClient sftp, String path) {
        try {
            sftp.remove(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void fail(ForwardJob j, String error, String reason, boolean permanent) {
        app.metrics.forwardFailure(j.destinationId(), reason);
        long now = System.currentTimeMillis();
        long first = j.firstFailedAt() == null ? now : j.firstFailedAt();
        String msg = error == null ? reason : error.length() > 900 ? error.substring(0, 900) : error;
        if (permanent || now - first >= retryFor) {
            app.records.updateJob(j.id(), JobState.FAILED, msg, 0, first, null);
            Audit.log("forward", "file", j.fileName(), "destination", j.destinationId(), "outcome", "failed",
                    "error", msg);
            return;
        }
        long backoff = backoffOverride >= 0 ? backoffOverride
                : Math.min(MAX_BACKOFF, MIN_BACKOFF << Math.min(10, Math.max(0, j.attempts())));
        app.records.updateJob(j.id(), JobState.RETRY_WAIT, msg, now + backoff, first, null);
    }

    /** Deletes the file from fdrop after sending, unless the folder keeps files for some days. */
    private void afterSent(Folder f, ForwardJob j) {
        Optional<FolderForward> ff = app.folders.forward(f.id());
        if (ff.isPresent() && ff.get().keepDays() > 0) {
            return;
        }
        app.janitor.deleteForwarded(f, j);
    }

    @Override
    public void close() {
        running = false;
        poller.shutdownNow();
    }
}
