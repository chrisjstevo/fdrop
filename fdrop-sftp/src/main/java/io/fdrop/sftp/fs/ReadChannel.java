package io.fdrop.sftp.fs;

import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.service.FileService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;

/**
 * A seekable read over a stored file. SFTP reads carry explicit offsets; sequential reads reuse one backend stream
 * and a seek reopens it at the new offset (a ranged GET on S3).
 */
final class ReadChannel implements SeekableByteChannel {

    private final FileService files;
    private final FileService.ReadTarget target;
    private final FdropMetrics metrics;
    private ReadableByteChannel current;
    private long currentPos = -1;
    private long position;
    private long read;
    private boolean open = true;

    ReadChannel(FileService files, FileService.ReadTarget target, FdropMetrics metrics) {
        this.files = files;
        this.target = target;
        this.metrics = metrics;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (position >= target.info().size()) {
            return -1;
        }
        if (current == null || currentPos != position) {
            if (current != null) {
                current.close();
            }
            current = files.openRead(target, position, -1);
            currentPos = position;
        }
        int n = current.read(dst);
        if (n > 0) {
            position += n;
            currentPos += n;
            read += n;
        }
        return n;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
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
        return target.info().size();
    }

    @Override
    public SeekableByteChannel truncate(long size) {
        throw new NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        metrics.transfer("sftp", "download", read);
        if (current != null) {
            current.close();
        }
    }
}
