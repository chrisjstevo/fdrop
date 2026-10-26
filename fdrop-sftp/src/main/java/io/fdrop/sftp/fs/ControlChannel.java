package io.fdrop.sftp.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * An SFTP expiry control file ({@code <name>.fdrop-expires}) held in memory. Never stored: reading returns the
 * target file's expiry, and the text written is handed to {@link OnClose} when the handle closes.
 */
final class ControlChannel implements SeekableByteChannel {

    static final int MAX_BYTES = 256;

    /** Receives the written text; a failure fails the close, so the client sees the message. */
    interface OnClose {
        void accept(String text) throws IOException;
    }

    private final byte[] data;
    private final OnClose onClose;
    private int size;
    private long position;
    private boolean open = true;

    private ControlChannel(byte[] data, int size, OnClose onClose) {
        this.data = data;
        this.size = size;
        this.onClose = onClose;
    }

    static ControlChannel forReading(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return new ControlChannel(b, b.length, null);
    }

    static ControlChannel forWriting(OnClose onClose) {
        return new ControlChannel(new byte[MAX_BYTES], 0, onClose);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (!open) {
            throw new ClosedChannelException();
        }
        if (onClose != null) {
            throw new NonReadableChannelException();
        }
        if (position >= size) {
            return -1;
        }
        int n = (int) Math.min(dst.remaining(), size - position);
        dst.put(data, (int) position, n);
        position += n;
        return n;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!open) {
            throw new ClosedChannelException();
        }
        if (onClose == null) {
            throw new NonWritableChannelException();
        }
        int n = src.remaining();
        if (position + n > MAX_BYTES) {
            throw new IOException("expiry control files hold at most " + MAX_BYTES + " bytes, such as '6 days'");
        }
        src.get(data, (int) position, n);
        position += n;
        size = (int) Math.max(size, position);
        return n;
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) {
        position = newPosition;
        return this;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long newSize) {
        size = (int) Math.min(size, Math.max(0, newSize));
        return this;
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
        if (onClose != null) {
            onClose.accept(new String(data, 0, size, StandardCharsets.UTF_8));
        }
    }
}
