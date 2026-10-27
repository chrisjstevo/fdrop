package io.fdrop.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One watcher per settings: an exclusive lock on {@code lock} in the watch's state folder, holding its PID. */
final class WatchLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private WatchLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static WatchLock acquire(Path file) {
        try {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            FileLock l;
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                l = null;
            }
            if (l == null) {
                String pid = Files.readString(file, StandardCharsets.UTF_8).strip();
                ch.close();
                throw new CliException(CliException.WATCH, "another fdrop watch is already running with these"
                        + " settings" + (pid.isEmpty() ? "" : " (PID " + pid + ")") + "; its lock is " + file);
            }
            ch.truncate(0);
            ch.write(ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8)), 0);
            ch.force(false);
            return new WatchLock(ch, l);
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot lock " + file + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // the lock goes with the process anyway
        }
    }
}
