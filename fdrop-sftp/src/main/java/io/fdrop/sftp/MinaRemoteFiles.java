package io.fdrop.sftp;

import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.service.RemoteFiles;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link RemoteFiles} over {@link ForwardClient}: the same mutually authenticated connection as sending, opened per
 * call and used read-only. Every path has its symbolic links resolved and is refused unless it is the remote directory
 * or below it.
 */
public final class MinaRemoteFiles implements RemoteFiles {

    private final ForwardClient client;

    public MinaRemoteFiles(ForwardClient client) {
        this.client = client;
    }

    @Override
    public List<RemoteEntry> list(ForwardDestination d, Login login, String root, String dir, int max)
            throws IOException {
        try (ClientSession session = open(d, login);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            String base = sftp.canonicalPath(root);
            String path = resolve(sftp, base, dir.substring(root.length()));
            List<RemoteEntry> out = new ArrayList<>();
            for (SftpClient.DirEntry e : sftp.readDir(path)) {
                String name = e.getFilename();
                if (name.equals(".") || name.equals("..")) {
                    continue;
                }
                SftpClient.Attributes a = e.getAttributes();
                if (a.isSymbolicLink()) {
                    // shown as what it points to; a broken link, or one leading out of the remote directory, is hidden
                    try {
                        a = sftp.stat(resolve(sftp, base, child(path, name).substring(base.length())));
                    } catch (SftpException | FdropException hidden) {
                        continue;
                    }
                }
                if (!a.isDirectory() && !a.isRegularFile()) {
                    continue;
                }
                out.add(entry(name, a));
                if (out.size() > max) {
                    break;
                }
            }
            return out;
        } catch (SftpException e) {
            throw notFoundOr(e, dir);
        }
    }

    @Override
    public RemoteEntry stat(ForwardDestination d, Login login, String root, String path) throws IOException {
        try (ClientSession session = open(d, login);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            String p = inside(sftp, root, path);
            return entry(p.substring(p.lastIndexOf('/') + 1), sftp.stat(p));
        } catch (SftpException e) {
            throw notFoundOr(e, path);
        }
    }

    @Override
    public void copy(ForwardDestination d, Login login, String root, String path, OutputStream out)
            throws IOException {
        try (ClientSession session = open(d, login);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
             InputStream in = sftp.read(inside(sftp, root, path))) {
            in.transferTo(out);
        } catch (SftpException e) {
            throw notFoundOr(e, path);
        }
    }

    private ClientSession open(ForwardDestination d, Login login) throws IOException {
        return client.open(d, MinaSshTools.loadPrivateKey(login.privateKeyOpenSsh()), login.userCert());
    }

    /**
     * {@code path} (which starts with {@code root}) with every symbolic link in it resolved, if it stays within
     * {@code root}; refused otherwise. Links are followed here, one component at a time, rather than left to the
     * server's {@code realpath}, which not every server resolves links in.
     */
    static String inside(SftpClient sftp, String root, String path) throws IOException {
        if (!path.startsWith(root)) {
            throw FdropException.forbidden("that path leads outside the destination's folder");
        }
        return resolve(sftp, sftp.canonicalPath(root), path.substring(root.length()));
    }

    /** {@code rel} below {@code base} (a canonical path) with its links resolved; refused if it leaves {@code base}. */
    private static String resolve(SftpClient sftp, String base, String rel) throws IOException {
        Deque<String> todo = new ArrayDeque<>(segments(rel));
        String cur = base;
        int hops = 0;
        while (!todo.isEmpty()) {
            String next = child(cur, todo.pop());
            if (!sftp.lstat(next).isSymbolicLink()) {
                cur = next;
                continue;
            }
            if (++hops > 40) {
                throw FdropException.forbidden("too many symbolic links");
            }
            String target = sftp.readLink(next);
            String resolved = normalize(target.startsWith("/") ? target : child(cur, target));
            if (!within(base, resolved)) {
                throw FdropException.forbidden("that path leads outside the destination's folder");
            }
            // start again from the top with the link's target in place of the link
            List<String> rest = new ArrayList<>(todo);
            todo.clear();
            todo.addAll(segments(resolved.substring(base.length())));
            todo.addAll(rest);
            cur = base;
        }
        return cur;
    }

    private static boolean within(String base, String p) {
        return p.equals(base) || p.startsWith(base.equals("/") ? "/" : base + "/");
    }

    private static List<String> segments(String p) {
        List<String> out = new ArrayList<>();
        for (String s : p.split("/")) {
            if (!s.isEmpty() && !s.equals(".")) {
                out.add(s);
            }
        }
        return out;
    }

    /** An absolute path with {@code .} and {@code ..} worked out, never above {@code /}. */
    static String normalize(String p) {
        Deque<String> out = new ArrayDeque<>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                out.pollLast();
            } else {
                out.addLast(s);
            }
        }
        return "/" + String.join("/", out);
    }

    private static String child(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static RemoteEntry entry(String name, SftpClient.Attributes a) {
        FileTime t = a.getModifyTime();
        return new RemoteEntry(name, a.isDirectory(), a.isDirectory() ? 0 : a.getSize(),
                t == null ? 0 : t.toMillis());
    }

    private static IOException notFoundOr(SftpException e, String path) {
        if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) {
            throw FdropException.notFound("not on the destination any more: "
                    + path.substring(path.lastIndexOf('/') + 1));
        }
        if (e.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED) {
            throw FdropException.forbidden("the destination does not let fdrop read that");
        }
        return e;
    }
}
