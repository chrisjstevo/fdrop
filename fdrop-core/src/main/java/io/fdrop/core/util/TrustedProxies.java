package io.fdrop.core.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Works out the real client address behind reverse proxies and load balancers ({@code http.trustedProxies}).
 * Forwarding headers are believed only when the TCP peer is a trusted proxy, and the address used is the rightmost
 * one not itself a trusted proxy, so a client cannot fake its address by sending the header.
 */
public final class TrustedProxies {

    private final List<String> cidrs;

    public TrustedProxies(List<String> cidrs) {
        this.cidrs = List.copyOf(cidrs);
    }

    public boolean isTrusted(InetAddress addr) {
        for (String c : cidrs) {
            if (Cidr.contains(c, addr)) {
                return true;
            }
        }
        return false;
    }

    public boolean isTrusted(String ip) {
        try {
            return isTrusted(InetAddress.getByName(strip(ip)));
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    /**
     * @param peer      the TCP peer address
     * @param forwarded the {@code Forwarded} header, or null
     * @param xff       the {@code X-Forwarded-For} header, or null
     */
    public String clientIp(InetAddress peer, String forwarded, String xff) {
        String peerIp = peer.getHostAddress();
        if (!isTrusted(peer)) {
            return normalise(peerIp);
        }
        List<String> chain = new ArrayList<>();
        if (forwarded != null && !forwarded.isBlank()) {
            for (String element : forwarded.split(",")) {
                for (String pair : element.split(";")) {
                    String p = pair.trim();
                    if (p.toLowerCase(Locale.ROOT).startsWith("for=")) {
                        chain.add(strip(p.substring(4)));
                    }
                }
            }
        } else if (xff != null && !xff.isBlank()) {
            for (String part : xff.split(",")) {
                chain.add(strip(part.trim()));
            }
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            String candidate = chain.get(i);
            if (candidate.isEmpty() || candidate.equalsIgnoreCase("unknown") || candidate.startsWith("_")) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return normalise(candidate);
            }
        }
        return normalise(peerIp);
    }

    /** Removes quotes, brackets and ports from a forwarded address. */
    static String strip(String s) {
        String v = s.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            return end > 0 ? v.substring(1, end) : v;
        }
        int colon = v.indexOf(':');
        if (colon > 0 && v.indexOf(':', colon + 1) < 0) {
            return v.substring(0, colon);
        }
        return v;
    }

    /** IPv6 in compressed form, IPv4-mapped IPv6 as IPv4. */
    public static String normalise(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            String h = a.getHostAddress();
            if (h.contains(":")) {
                return compress(h);
            }
            return h;
        } catch (UnknownHostException | RuntimeException e) {
            return ip;
        }
    }

    private static String compress(String full) {
        int scope = full.indexOf('%');
        String addr = scope >= 0 ? full.substring(0, scope) : full;
        String[] groups = addr.split(":");
        for (int i = 0; i < groups.length; i++) {
            groups[i] = groups[i].replaceFirst("^0+(?=.)", "");
        }
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < groups.length; ) {
            if (groups[i].equals("0")) {
                int j = i;
                while (j < groups.length && groups[j].equals("0")) {
                    j++;
                }
                if (j - i > bestLen && j - i > 1) {
                    bestStart = i;
                    bestLen = j - i;
                }
                i = j;
            } else {
                i++;
            }
        }
        if (bestStart < 0) {
            return String.join(":", groups);
        }
        String left = String.join(":", java.util.Arrays.copyOfRange(groups, 0, bestStart));
        String right = String.join(":", java.util.Arrays.copyOfRange(groups, bestStart + bestLen, groups.length));
        return left + "::" + right;
    }
}
