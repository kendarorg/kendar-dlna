package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Path-level network ACL by address, trusted-proxy handling of X-Forwarded-*, and the config of both. */
class NetworkPolicyTest {

    JettyHost host;

    @AfterEach
    void stop() throws Exception {
        if (host != null) {
            host.stop();
        }
    }

    // ---- pure policy ----

    @Test
    void cidrMatchesV4V6AndMappedAddresses() {
        assertThat(Cidr.parse("10.0.0.0/8").contains("10.200.1.1")).isTrue();
        assertThat(Cidr.parse("10.0.0.0/8").contains("11.0.0.1")).isFalse();
        assertThat(Cidr.parse("172.16.0.0/12").contains("172.31.255.255")).isTrue();
        assertThat(Cidr.parse("172.16.0.0/12").contains("172.32.0.1")).isFalse();
        assertThat(Cidr.parse("192.168.1.40").contains("192.168.1.40")).isTrue();
        assertThat(Cidr.parse("192.168.1.40").contains("192.168.1.41")).isFalse();
        assertThat(Cidr.parse("fc00::/7").contains("fd12:3456::1")).isTrue();
        assertThat(Cidr.parse("fc00::/7").contains("2001:db8::1")).isFalse();
        assertThat(Cidr.parse("10.0.0.0/8").contains("::ffff:10.1.2.3")).isTrue();
        assertThat(Cidr.parse("10.0.0.0/8").contains("not an address")).isFalse();
        assertThat(Cidr.parse("0.0.0.0/0").contains("8.8.8.8")).isTrue();
        assertThatThrownBy(() -> Cidr.parse("example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adminAndDlnaPathsAreLanOnlyEverythingElseOpen() {
        NetworkPolicy p = NetworkPolicy.defaults();
        for (String path : new String[] { "/admin", "/admin/", "/admin/js/app.js", "/api/v1/admin/users",
                "/api/v1/admin", "/upnp/desc.xml", "/stream/a/b", "/art/t/1" }) {
            assertThat(NetworkPolicy.lanOnly(path)).as(path).isTrue();
            assertThat(p.allows(path, "8.8.8.8")).as(path).isFalse();
            assertThat(p.allows(path, "192.168.1.9")).as(path).isTrue();
        }
        for (String path : new String[] { "/", "/health", "/api/v1/auth/login", "/api/v1/library/songs",
                "/api/v1/stream", "/api/v1/me", "/player/", "/administrator", "/api/v1/administrators" }) {
            assertThat(NetworkPolicy.lanOnly(path)).as(path).isFalse();
            assertThat(p.allows(path, "8.8.8.8")).as(path).isTrue();
        }
    }

    @Test
    void defaultLanIsLoopbackAndPrivateRangesAndItIsConfigurable() {
        NetworkPolicy d = NetworkPolicy.defaults();
        for (String a : new String[] { "127.0.0.1", "10.1.2.3", "172.20.0.5", "192.168.0.7", "169.254.1.1", "::1",
                "fd00::1", "fe80::1" }) {
            assertThat(d.isLan(a)).as(a).isTrue();
        }
        for (String a : new String[] { "8.8.8.8", "172.32.0.1", "2001:db8::1", "unknown" }) {
            assertThat(d.isLan(a)).as(a).isFalse();
        }
        NetworkPolicy custom = new NetworkPolicy(List.of("203.0.113.0/24"), null);
        assertThat(custom.isLan("203.0.113.9")).isTrue();
        assertThat(custom.isLan("192.168.1.1")).isFalse();
        assertThat(custom.allows("/admin/", "192.168.1.1")).isFalse();
    }

    @Test
    void forwardedHeadersCountOnlyFromTrustedProxies() {
        NetworkPolicy p = new NetworkPolicy(null, List.of("172.18.0.0/16"));
        // untrusted peer: headers ignored (cannot claim a LAN address or HTTPS)
        NetworkPolicy.Client spoof = p.resolve("8.8.8.8", false, "192.168.1.5", "https");
        assertThat(spoof.address()).isEqualTo("8.8.8.8");
        assertThat(spoof.secure()).isFalse();
        // trusted peer: client from X-Forwarded-For, scheme from X-Forwarded-Proto
        NetworkPolicy.Client c = p.resolve("172.18.0.2", false, "203.0.113.7", "https");
        assertThat(c.address()).isEqualTo("203.0.113.7");
        assertThat(c.secure()).isTrue();
        assertThat(p.resolve("172.18.0.2", false, "203.0.113.7", "http").secure()).isFalse();
        // no header: the proxy itself
        assertThat(p.resolve("172.18.0.2", false, null, null).address()).isEqualTo("172.18.0.2");
        // chain: the client-supplied left part is not trusted, the right-most untrusted hop wins
        assertThat(p.resolve("172.18.0.2", false, "192.168.1.5, 203.0.113.7", null).address())
                .isEqualTo("203.0.113.7");
        assertThat(p.resolve("172.18.0.2", false, "203.0.113.7, 172.18.0.9", null).address())
                .isEqualTo("203.0.113.7");
        assertThat(p.resolve("172.18.0.2", false, "garbage", null).address()).isEqualTo("172.18.0.2");
        // a secure connection stays secure
        assertThat(p.resolve("172.18.0.2", true, null, "http").secure()).isTrue();
    }

    @Test
    void configValidationRejectsBadRanges() {
        AppConfig bad = new AppConfig(new AppConfig.Library(null, null, null),
                new AppConfig.Network(null, null, List.of("10.0.0.0/8"), List.of("proxy.local")), null);
        assertThatThrownBy(() -> ConfigLoader.validate(bad)).hasMessageContaining("network.trustedProxies");
        AppConfig ok = new AppConfig(new AppConfig.Library(null, null, null),
                new AppConfig.Network(null, null, List.of("10.0.0.0/8"), List.of("172.18.0.1")), null);
        ConfigLoader.validate(ok);
    }

    // ---- through a real Jetty ----

    private HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JettyHost start(NetworkPolicy policy) throws Exception {
        host = new JettyHost(0, policy);
        // echoes what the application would see
        ContextHandler echo = new ContextHandler("/api/v1/echo");
        echo.setAllowNullPathInContext(true);
        echo.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
                String body = Request.getRemoteAddr(request) + " " + request.getConnectionMetaData().isSecure();
                response.write(true, ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)), callback);
                return true;
            }
        });
        host.contexts().addHandler(echo);
        ContextHandler admin = new ContextHandler("/api/v1/admin");
        admin.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.setStatus(200);
                callback.succeeded();
                return true;
            }
        });
        host.contexts().addHandler(admin);
        host.start();
        return host;
    }

    @Test
    void aClientOutsideTheLanCannotReachAdminOrDlnaPathsButTheWebApiIsOpen() throws Exception {
        start(new NetworkPolicy(List.of("10.0.0.0/8"), null));   // the test client is 127.0.0.1: not LAN here
        assertThat(get("/api/v1/admin/users").statusCode()).isEqualTo(403);
        assertThat(get("/admin/").statusCode()).isEqualTo(403);
        assertThat(get("/upnp/x").statusCode()).isEqualTo(403);
        assertThat(get("/stream/a/b").statusCode()).isEqualTo(403);
        assertThat(get("/art/t/1").statusCode()).isEqualTo(403);
        assertThat(get("/health").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/echo").statusCode()).isEqualTo(200);
    }

    @Test
    void aLanClientReachesEverything() throws Exception {
        start(NetworkPolicy.defaults());   // 127.0.0.1 is LAN
        assertThat(get("/api/v1/admin/users").statusCode()).isEqualTo(200);
        assertThat(get("/admin/x").statusCode()).isEqualTo(404);   // allowed through, no such asset in this host
        assertThat(get("/health").statusCode()).isEqualTo(200);
    }

    @Test
    void forwardedHeadersFromAnUntrustedPeerCannotOpenTheAdminPaths() throws Exception {
        start(new NetworkPolicy(List.of("10.0.0.0/8"), null));
        assertThat(get("/api/v1/admin/users", "X-Forwarded-For", "10.1.1.1").statusCode()).isEqualTo(403);
        HttpResponse<String> echo = get("/api/v1/echo", "X-Forwarded-For", "10.1.1.1", "X-Forwarded-Proto", "https");
        assertThat(echo.body()).isEqualTo("127.0.0.1 false");
    }

    @Test
    void aTrustedProxyDecidesTheEffectiveClient() throws Exception {
        start(new NetworkPolicy(List.of("10.0.0.0/8"), List.of("127.0.0.1")));
        // the proxy forwards an internet client: admin refused
        assertThat(get("/api/v1/admin/users", "X-Forwarded-For", "203.0.113.7").statusCode()).isEqualTo(403);
        // a LAN client through the proxy: allowed
        assertThat(get("/api/v1/admin/users", "X-Forwarded-For", "10.1.1.1").statusCode()).isEqualTo(200);
        // a LAN address smuggled in front of the real client does not help
        assertThat(get("/api/v1/admin/users", "X-Forwarded-For", "10.1.1.1, 203.0.113.7").statusCode())
                .isEqualTo(403);
        // without the header the client is the proxy (127.0.0.1: not LAN in this config)
        assertThat(get("/api/v1/admin/users").statusCode()).isEqualTo(403);
        // the application sees the forwarded address and scheme
        HttpResponse<String> echo = get("/api/v1/echo", "X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https");
        assertThat(echo.body()).isEqualTo("203.0.113.7 true");
        assertThat(get("/api/v1/echo", "X-Forwarded-For", "203.0.113.7").body()).isEqualTo("203.0.113.7 false");
        assertThat(get("/api/v1/echo").body()).isEqualTo("127.0.0.1 false");
    }
}
