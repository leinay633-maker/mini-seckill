package com.example.miniseckill.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the client IP used for IP-dimension rate limiting.
 *
 * <p>By default X-Forwarded-For / X-Real-IP are NOT trusted: a client can set them freely, and the
 * old {@code xff.split(",")[0]} took the left-most (most spoofable) hop, letting anyone rotate the
 * header to bypass IP limits. Only when {@code seckill.security.trust-forwarded-header=true} (i.e. the
 * app is behind a trusted proxy that appends the real client) do we parse XFF, peeling trusted-proxy
 * hops off the right and taking the first address that is not itself a trusted proxy.
 */
@Component
public class ClientIpResolver {

    private final SeckillProperties seckillProperties;

    public ClientIpResolver(SeckillProperties seckillProperties) {
        this.seckillProperties = seckillProperties;
    }

    public String resolve(HttpServletRequest request) {
        SeckillProperties.Security security = seckillProperties.getSecurity();
        if (!security.isTrustForwardedHeader()) {
            return request.getRemoteAddr();
        }

        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr, security.getTrustedProxies())) {
            // Forwarded headers are authoritative only when the direct peer is a configured proxy.
            // Otherwise a client connected straight to the app could forge XFF/X-Real-IP and rotate
            // the apparent address to bypass IP-dimension rate limiting.
            return remoteAddr;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String client = pickClientFromForwardedFor(forwardedFor, security.getTrustedProxies());
            if (client != null) {
                return client;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return remoteAddr;
    }

    private String pickClientFromForwardedFor(String forwardedFor, List<String> trustedProxies) {
        String[] hops = forwardedFor.split(",");
        // Right-to-left: skip trusted proxy hops, return the first non-proxy address (the real client).
        for (int i = hops.length - 1; i >= 0; i--) {
            String ip = hops[i].trim();
            if (ip.isEmpty()) {
                continue;
            }
            if (!isTrustedProxy(ip, trustedProxies)) {
                return ip;
            }
        }
        // Every hop is a trusted proxy; fall back to the left-most entry.
        String first = hops[0].trim();
        return first.isEmpty() ? null : first;
    }

    private boolean isTrustedProxy(String ip, List<String> trustedProxies) {
        if (trustedProxies == null) {
            return false;
        }
        for (String entry : trustedProxies) {
            if (!StringUtils.hasText(entry)) {
                continue;
            }
            // An entry ending in '.' is a network prefix (e.g. "10.", "192.168."); otherwise it is an
            // exact IP. Using startsWith for exact IPs would over-match ("1.2.3.4" would trust
            // "1.2.3.45"), leaving an XFF-spoofing gap — so match those with equals.
            boolean matched = entry.endsWith(".") ? ip.startsWith(entry) : ip.equals(entry);
            if (matched) {
                return true;
            }
        }
        return false;
    }
}
