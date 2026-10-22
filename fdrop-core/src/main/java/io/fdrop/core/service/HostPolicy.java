package io.fdrop.core.service;

import io.fdrop.core.error.FdropException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * {@code forward.allowedHosts}: the server-wide limit on where forward connections may go. Entries are exact host
 * names, wildcard names ({@code *.partner.example}), IP addresses or CIDR ranges. A host matched only by a wildcard
 * must not resolve to a private, loopback or link-local address; one listed exactly, or whose addresses fall in a
 * listed range, may.
 */
public final class HostPolicy {

    private final List<String> entries;

    public HostPolicy(List<String> entries) {
        this.entries = entries.stream().map(e -> e.trim().toLowerCase(Locale.ROOT)).filter(e -> !e.isEmpty()).toList();
    }

    public boolean enabled() {
        return !entries.isEmpty();
    }

    public void check(String rawHost) {
        if (entries.isEmpty()) {
            throw FdropException.forbidden("forwarding is disabled: forward.allowedHosts is empty");
        }
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (entries.contains(host)) {
            return;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw FdropException.badRequest("cannot resolve host " + rawHost);
        }
        boolean inRange = true;
        for (InetAddress a : addrs) {
            if (entries.stream().noneMatch(e -> e.contains("/") && inCidr(a, e) || e.equals(a.getHostAddress()))) {
                inRange = false;
                break;
            }
        }
        if (inRange) {
            return;
        }
        boolean wildcard = entries.stream().anyMatch(e -> e.startsWith("*.") && host.endsWith(e.substring(1)));
        if (!wildcard) {
            throw FdropException.forbidden(rawHost + " is not in forward.allowedHosts");
        }
        for (InetAddress a : addrs) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                    || isUniqueLocal(a)) {
                throw FdropException.forbidden(rawHost + " resolves to a private address; list it exactly in"
                        + " forward.allowedHosts to allow it");
            }
        }
    }

    private static boolean isUniqueLocal(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    static boolean inCidr(InetAddress addr, String cidr) {
        return io.fdrop.core.util.Cidr.contains(cidr, addr);
    }
}
