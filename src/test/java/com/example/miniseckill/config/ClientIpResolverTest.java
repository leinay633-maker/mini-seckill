package com.example.miniseckill.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    @Test
    void ignoresSpoofedForwardedHeadersByDefault() {
        SeckillProperties properties = new SeckillProperties();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.20");
        request.addHeader("X-Real-IP", "198.51.100.21");

        String clientIp = new ClientIpResolver(properties).resolve(request);

        assertEquals("203.0.113.10", clientIp);
    }

    @Test
    void peelsTrustedProxyHopsFromRightWhenForwardedHeadersAreEnabled() {
        SeckillProperties properties = forwardedHeaderProperties(List.of("10."));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.3");
        request.addHeader("X-Forwarded-For", "198.51.100.20, 10.0.0.1, 10.0.0.2");

        String clientIp = new ClientIpResolver(properties).resolve(request);

        assertEquals("198.51.100.20", clientIp);
    }

    @Test
    void ignoresForwardedHeadersWhenDirectPeerIsNotTrusted() {
        SeckillProperties properties = forwardedHeaderProperties(List.of("10."));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.20, 10.0.0.1");
        request.addHeader("X-Real-IP", "198.51.100.21");

        String clientIp = new ClientIpResolver(properties).resolve(request);

        assertEquals("203.0.113.10", clientIp);
    }

    @Test
    void exactTrustedProxyDoesNotMatchAnotherIpWithTheSamePrefix() {
        SeckillProperties properties = forwardedHeaderProperties(List.of("1.2.3.4"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("1.2.3.4");
        request.addHeader("X-Forwarded-For", "198.51.100.20, 1.2.3.45");

        String clientIp = new ClientIpResolver(properties).resolve(request);

        assertEquals("1.2.3.45", clientIp);
    }

    private SeckillProperties forwardedHeaderProperties(List<String> trustedProxies) {
        SeckillProperties properties = new SeckillProperties();
        properties.getSecurity().setTrustForwardedHeader(true);
        properties.getSecurity().setTrustedProxies(trustedProxies);
        return properties;
    }
}
