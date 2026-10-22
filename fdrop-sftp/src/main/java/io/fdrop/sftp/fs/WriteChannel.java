package io.fdrop.sftp.fs;

import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.service.FileService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Map;
import java.util.TreeMap;

/**
 * An SFTP upload streamed straight into storage. Writes are expected in order; writes that arrive slightly ahead
 * (pipelining clients) wait in a 16 MiB reorder window. Resuming ({@code reput}) and {@code APPEND} first copy the
 * existing file into the new upload, then continue from its end. The file only becomes visible on close; a
 * dropped session discards it.
 */
final class WriteChannel implements SeekableByteChannel {

    static final long WINDOW = 16L * 1024 * 1024;

    private final FileService files;
    private final FileService.FileWrite write;
    private final FdropMetrics metrics;
    /** Set when the target exists and was not truncated: a write at its end resumes it. */
    private FileService.ReadTarget resumeFrom;
    private final boolean append;
    private final TreeMap<Long, byte[]> pending = new TreeMap<>();
    private long pendingBytes;
    private long position;
    private boolean started;
    private boolean open = true;

    WriteChannel(FileService files, FileService.FileWrite write, FileService.ReadTarget resumeFrom, boolean append,
                 FdropMetrics metrics) {
        this.files = files;
        this.write = write;
        this.resumeFrom = resumeFrom;
        this.append = append;
        this.metrics = metrics;
        if (append && resumeFrom != null) {
            this.position = resumeFrom.info().size();
        }
    }

    private void start(long offset) throws IOException {
        started = true;
        if (resumeFrom == null) {
            return;
        }
        long existing = resumeFrom.info().size();
        if (offset == 0 && !append) {
            // writing from the start: a plain overwrite
            resumeFrom = null;
            return;
        }
        if (offset != existing) {
            throw new IOException("can only resume at the end of the existing file (" + existing + " bytes)");
        }
        try (ReadableByteChannel in = files.openRead(resumeFrom, 0, -1)) {
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            while (in.read(buf) >= 0) {
                buf.flip();
                while (buf.hasRemaining()) {
                    write.write(buf);
                }
                buf.clear();
            }
        }
        resumeFrom = null;
    }

    FileService.FileWrite fileWrite() {
        return write;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!open) {
            throw new java.nio.channels.ClosedChannelException();
        }
        int n = src.remaining();
        if (!started) {
            start(position);
        }
        try {
            long written = write.written();
            if (position == written) {
                while (src.hasRemaining()) {
                    write.write(src);
                }
                drainPending();
            } else if (position > written) {
                if (pendingBytes + n > WINDOW) {
                    throw new IOException("writes too far out of order (gap at offset " + written + ")");
                }
                byte[] copy = new byte[n];
                src.get(copy);
                pending.put(position, copy);
                pendingBytes += n;
            } else {
                throw new IOException("rewriting earlier parts of a file is not supported (offset " + position
                        + " < " + written + ")");
            }
        } catch (FdropException e) {
            throw FdropFileSystemProvider.toIo(e, write.path().virtual());
        }
        position += n;
        return n;
    }

    private void drainPending() throws IOException {
        while (!pending.isEmpty()) {
            Map.Entry<Long, byte[]> first = pending.firstEntry();
            long written = write.written();
            if (first.getKey() > written) {
                return;
            }
            pending.pollFirstEntry();
            pendingBytes -= first.getValue().length;
            int skip = (int) (written - first.getKey());
            if (skip < first.getValue().length) {
                ByteBuffer b = ByteBuffer.wrap(first.getValue(), skip, first.getValue().length - skip);
                while (b.hasRemaining()) {
                    write.write(b);
                }
            }
        }
    }

    @Override
    public int read(ByteBuffer dst) {
        throw new NonReadableChannelException();
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) {
        this.position = newPosition;
        return this;
    }

    @Override
    public long size() {
        long end = write.written();
        if (!pending.isEmpty()) {
            Map.Entry<Long, byte[]> last = pending.lastEntry();
            end = Math.max(end, last.getKey() + last.getValue().length);
        }
        if (resumeFrom != null) {
            end = Math.max(end, resumeFrom.info().size());
        }
        return end;
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        if (size < size()) {
            throw new IOException("truncating an upload is not supported");
        }
        return this;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** Commits the file. Gaps left by missing writes fail the upload instead of storing a corrupt file. */
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        try {
            if (!started) {
                start(position);
            }
            if (!pending.isEmpty()) {
                write.abort();
                throw new IOException("upload incomplete: bytes missing before offset " + pending.firstKey());
            }
            long bytes = write.written();
            write.commit();
            metrics.transfer("sftp", "upload", bytes);
        } catch (FdropException e) {
            write.abort();
            throw FdropFileSystemProvider.toIo(e, write.path().virtual());
        } catch (IOException | RuntimeException e) {
            write.abort();
            throw e;
        }
    }

    /** Discards the upload without committing, when the session drops. */
    void abort() {
        open = false;
        write.abort();
    }
}
