package io.fdrop.sftp;

import io.fdrop.core.util.TrustedProxies;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.session.ServerProxyAcceptor;
import org.apache.sshd.server.session.ServerSession;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Reads the HAProxy PROXY protocol header (v1 text or v2 binary) that a TCP load balancer sends before the SSH
 * banner, so fdrop records the real client address. Headers are believed only from {@code http.trustedProxies};
 * a connection from anywhere else is taken at face value.
 */
final class ProxyProtocolAcceptor implements ServerProxyAcceptor {

    static final AttributeKey<String> CLIENT_IP = new AttributeKey<>();
    private static final byte[] V2_SIG = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

    private final TrustedProxies proxies;

    ProxyProtocolAcceptor(TrustedProxies proxies) {
        this.proxies = proxies;
    }

    @Override
    public boolean acceptServerProxyMetadata(ServerSession session, Buffer buffer) throws Exception {
        InetAddress peer = ((InetSocketAddress) session.getClientAddress()).getAddress();
        if (!proxies.isTrusted(peer)) {
            return true;
        }
        int available = buffer.available();
        if (available == 0) {
            return false;
        }
        byte[] data = buffer.array();
        int rpos = buffer.rpos();
        if (data[rpos] == 'P') {
            return v1(session, buffer, data, rpos, available);
        }
        if (data[rpos] == 0x0D) {
            return v2(session, buffer, data, rpos, available);
        }
        // a trusted peer sending plain SSH: no header
        return true;
    }

    private static boolean v1(ServerSession session, Buffer buffer, byte[] data, int rpos, int available) {
        int max = Math.min(available, 107);
        for (int i = 0; i + 1 < max; i++) {
            if (data[rpos + i] == '\r' && data[rpos + i + 1] == '\n') {
                String line = new String(data, rpos, i, StandardCharsets.US_ASCII);
                String[] f = line.split(" ");
                if (f.length >= 6 && f[0].equals("PROXY") && (f[1].equals("TCP4") || f[1].equals("TCP6"))) {
                    session.setAttribute(CLIENT_IP, f[2]);
                }
                buffer.rpos(rpos + i + 2);
                return true;
            }
        }
        if (available >= 107) {
            throw new IllegalStateException("malformed PROXY v1 header");
        }
        return false;
    }

    private static boolean v2(ServerSession session, Buffer buffer, byte[] data, int rpos, int available)
            throws Exception {
        if (available < 16) {
            return false;
        }
        for (int i = 0; i < V2_SIG.length; i++) {
            if (data[rpos + i] != V2_SIG[i]) {
                return true;
            }
        }
        int verCmd = data[rpos + 12] & 0xff;
        int fam = data[rpos + 13] & 0xff;
        int len = ((data[rpos + 14] & 0xff) << 8) | (data[rpos + 15] & 0xff);
        if (available < 16 + len) {
            return false;
        }
        boolean proxy = (verCmd & 0x0f) == 1;
        int base = rpos + 16;
        if (proxy && (fam >> 4) == 1 && len >= 12) {
            byte[] a = {data[base], data[base + 1], data[base + 2], data[base + 3]};
            session.setAttribute(CLIENT_IP, InetAddress.getByAddress(a).getHostAddress());
        } else if (proxy && (fam >> 4) == 2 && len >= 36) {
            byte[] a = new byte[16];
            System.arraycopy(data, base, a, 0, 16);
            session.setAttribute(CLIENT_IP, InetAddress.getByAddress(a).getHostAddress());
        }
        buffer.rpos(rpos + 16 + len);
        return true;
    }
}
