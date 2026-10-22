package io.fdrop.core.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** CIDR range matching for IPv4 and IPv6. */
public final class Cidr {

    private Cidr() {
    }

    /** True when {@code addr} is inside {@code cidr} ({@code 10.0.0.0/8}); a bare address matches only itself. */
    public static boolean contains(String cidr, InetAddress addr) {
        try {
            String[] parts = cidr.trim().split("/");
            byte[] net = InetAddress.getByName(parts[0]).getAddress();
            byte[] ip = addr.getAddress();
            if (net.length != ip.length) {
                return false;
            }
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : net.length * 8;
            for (int i = 0; i < net.length && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xff : (0xff << (8 - bits)) & 0xff;
                if ((net[i] & mask) != (ip[i] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }
}
