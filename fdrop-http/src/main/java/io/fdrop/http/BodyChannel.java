package io.fdrop.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A request body handed from the Netty event loop to a handler's virtual thread as a blocking channel. Bytes are
 * never aggregated: when more than {@link #HIGH} bytes are queued, reading from the socket pauses
 * ({@code autoRead=false}) until the handler has caught up, so a slow backend write slows the client rather than
 * filling the heap.
 */
final class BodyChannel implements ReadableByteChannel {

    static final long HIGH = 1024 * 1024;
    static final long LOW = 256 * 1024;
    private static final ByteBuf END = Unpooled.unreleasableBuffer(Unpooled.EMPTY_BUFFER);

    private final Channel channel;
    private final LinkedBlockingQueue<ByteBuf> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queued = new AtomicLong();
    private volatile IOException failure;
    private volatile boolean paused;
    private volatile boolean ended;
    private volatile boolean finished;
    private ByteBuf current;
    private boolean open = true;
    private long consumed;

    BodyChannel(Channel channel) {
        this.channel = channel;
    }

    /** Event loop: a chunk arrived. Takes ownership of {@code buf}. */
    void offer(ByteBuf buf) {
        if (finished) {
            buf.release();
            return;
        }
        long now = queued.addAndGet(buf.readableBytes());
        queue.add(buf);
        if (now > HIGH && !paused) {
            paused = true;
            channel.config().setAutoRead(false);
        }
    }

    /** Event loop: the last chunk arrived. */
    void end() {
        ended = true;
        queue.add(END);
    }

    /** Event loop: the connection died. */
    void fail(IOException e) {
        failure = e;
        queue.add(END);
    }

    /** True once every byte of the request has arrived. */
    boolean complete() {
        return ended;
    }

    /** True once the handler has read to the end. */
    boolean fullyRead() {
        return finished;
    }

    long consumed() {
        return consumed;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (finished) {
            return -1;
        }
        while (current == null || !current.isReadable()) {
            if (current != null) {
                current.release();
                current = null;
            }
            ByteBuf next;
            try {
                next = queue.poll(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while reading the request body", e);
            }
            if (next == null) {
                throw new IOException("timed out waiting for the request body");
            }
            if (next == END) {
                if (failure != null) {
                    throw failure;
                }
                finished = true;
                return -1;
            }
            current = next;
        }
        int n = Math.min(dst.remaining(), current.readableBytes());
        int limit = dst.limit();
        dst.limit(dst.position() + n);
        current.readBytes(dst);
        dst.limit(limit);
        consumed += n;
        long left = queued.addAndGet(-n);
        if (paused && left < LOW) {
            paused = false;
            channel.config().setAutoRead(true);
            channel.read();
        }
        return n;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** Releases any buffers still queued. */
    @Override
    public void close() {
        open = false;
        finished = true;
        if (current != null) {
            current.release();
            current = null;
        }
        ByteBuf b;
        while ((b = queue.poll()) != null) {
            if (b != END) {
                b.release();
            }
        }
        if (paused) {
            paused = false;
            channel.config().setAutoRead(true);
        }
    }
}
