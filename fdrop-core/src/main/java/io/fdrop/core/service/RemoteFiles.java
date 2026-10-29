package io.fdrop.core.service;

import io.fdrop.core.model.Records.ForwardDestination;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Read-only access to a forward destination's remote directory, over the same mutually authenticated SFTP connection
 * as sending. Implemented in the SFTP module so the core stays free of SSH dependencies. Every path is absolute on the
 * server and must resolve, symbolic links followed, to {@code root} or below it; anything else is refused.
 */
public interface RemoteFiles {

    /** A file or folder on the destination; {@code modifiedAt} in epoch milliseconds. */
    record RemoteEntry(String name, boolean directory, long size, long modifiedAt) {
    }

    /** The login a connection uses: fdrop's private key for the destination (OpenSSH format) and its certificate. */
    record Login(byte[] privateKeyOpenSsh, String userCert) {
    }

    /** The entries of {@code dir}, at most {@code max + 1} so the caller can tell the listing was cut short. */
    List<RemoteEntry> list(ForwardDestination d, Login login, String root, String dir, int max) throws IOException;

    RemoteEntry stat(ForwardDestination d, Login login, String root, String path) throws IOException;

    /** Streams the file at {@code path} into {@code out}, which is left open. */
    void copy(ForwardDestination d, Login login, String root, String path, OutputStream out) throws IOException;
}
