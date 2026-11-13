package com.ratelimit.gateway.web;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** A set of IP addresses / CIDR ranges identifying the reverse proxies the gateway trusts. */
public final class TrustedProxies {

    private record Range(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false; // IPv4 never matches an IPv6 range and vice versa
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) {
        this.ranges = ranges;
    }

    /**
     * @param entries IP literals or CIDR ranges, e.g. {@code 172.18.0.2} or {@code 10.0.0.0/8}
     * @throws IllegalArgumentException for anything that is not a valid literal or prefix length,
     *                                  so that a typo fails at startup instead of silently
     *                                  trusting nobody (or everybody)
     */
    public static TrustedProxies of(List<String> entries) {
        List<Range> ranges = new ArrayList<>();
        for (String raw : entries) {
            ranges.add(parse(raw));
        }
        return new TrustedProxies(List.copyOf(ranges));
    }

    private static Range parse(String raw) {
        String entry = raw == null ? "" : raw.trim();
        String addressPart = entry;
        Integer prefix = null;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            addressPart = entry.substring(0, slash);
            try {
                prefix = Integer.valueOf(entry.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix length: '" + raw + "'");
            }
        }
        if (!InetAddresses.isInetAddress(addressPart)) {
            throw new IllegalArgumentException("Invalid trusted proxy address: '" + raw + "'");
        }
        byte[] bytes = InetAddresses.forString(addressPart).getAddress();
        int maxBits = bytes.length * 8;
        int bits = prefix == null ? maxBits : prefix;
        if (bits < 0 || bits > maxBits) {
            throw new IllegalArgumentException("Trusted proxy prefix length out of range: '" + raw + "'");
        }
        return new Range(bytes, bits);
    }

    public boolean isTrusted(InetAddress address) {
        byte[] bytes = address.getAddress();
        for (Range range : ranges) {
            if (range.contains(bytes)) {
                return true;
            }
        }
        return false;
    }
}
