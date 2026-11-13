package com.ratelimit.gateway.web;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.List;

/**
 * Works out the real client IP.
 *
 * <p>{@code X-Forwarded-For} is client-controlled, so it is only believed when the connection
 * comes from a trusted proxy, and even then it is read from the right: each trusted proxy appends
 * the address it received the request from, so the first address from the right that is not a
 * trusted proxy is the client. Anything a client prepends to the header is therefore ignored.
 *
 * <p>If an entry in the chain is not a plain IP literal (garbage, or a {@code host:port} form),
 * the chain is not trusted past that point and the last hop that could be validated is returned.
 * Results are returned in canonical text form so a given client always maps to the same
 * rate-limit key.
 */
public class ClientIpResolver {

    private final TrustedProxies trustedProxies;

    public ClientIpResolver(TrustedProxies trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    /**
     * @param remoteAddr         the socket peer address
     * @param forwardedForValues all values of the {@code X-Forwarded-For} header (may be empty)
     */
    public String resolve(String remoteAddr, List<String> forwardedForValues) {
        if (!InetAddresses.isInetAddress(remoteAddr)) {
            return remoteAddr; // not expected from a servlet container; nothing better to offer
        }
        InetAddress current = InetAddresses.forString(remoteAddr);
        if (!trustedProxies.isTrusted(current)) {
            return InetAddresses.toAddrString(current);
        }

        String[] chain = String.join(",", forwardedForValues).split(",", -1);
        if (chain.length == 1 && chain[0].isBlank()) {
            return InetAddresses.toAddrString(current); // header absent or empty
        }
        for (int i = chain.length - 1; i >= 0; i--) {
            String entry = chain[i].trim();
            if (!InetAddresses.isInetAddress(entry)) {
                return InetAddresses.toAddrString(current);
            }
            current = InetAddresses.forString(entry);
            if (!trustedProxies.isTrusted(current)) {
                return InetAddresses.toAddrString(current);
            }
        }
        return InetAddresses.toAddrString(current); // every hop was a trusted proxy
    }
}
