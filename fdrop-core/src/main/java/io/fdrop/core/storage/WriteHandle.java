package io.fdrop.core.storage;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/**
 * A streaming write. Bytes written are invisible until {@link #commit()}; {@link #abort()} or closing without commit
 * discards them. Implementations need not be thread-safe.
 */
public interface WriteHandle extends WritableByteChannel {

    /** Bytes written so far. */
    long written();

    /** Makes the object visible atomically and returns its final state. */
    ObjectInfo commit() throws IOException;

    /** Discards everything written. Safe to call more than once and after a failed commit. */
    void abort();

    /** Closing an uncommitted handle aborts it. */
    @Override
    void close();
}
