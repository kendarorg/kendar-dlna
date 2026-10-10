package org.kendar.dlna.platform;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/** The single shared Jetty server (one port, virtual threads for connections). */
public final class JettyHost {

    static final String HEALTH_BODY = "{\"status\":\"UP\"}";

    private final Server server;
    private final ServerConnector connector;
    private final ContextHandlerCollection contexts = new ContextHandlerCollection();

    public JettyHost(int port) {
        this(port, NetworkPolicy.defaults());
    }

    /**
     * @param policy the path-level network ACL and the trusted proxies: forwarded headers are applied to the
     *               request (client address, secure flag) only when the direct peer is a trusted proxy
     */
    public JettyHost(int port, NetworkPolicy policy) {
        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setName("jetty");
        pool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server = new Server(pool);
        HttpConfiguration http = new HttpConfiguration();
        http.addCustomizer((request, responseHeaders) -> forwarded(policy, request));
        connector = new ServerConnector(server, new HttpConnectionFactory(http));
        connector.setPort(port);
        server.addConnector(connector);
        server.setHandler(new Handler.Wrapper(contexts) {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                String path = request.getHttpURI().getPath();
                if (!policy.allows(path, Request.getRemoteAddr(request))) {
                    response.setStatus(403);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
                    response.write(true, java.nio.ByteBuffer.wrap("Forbidden: LAN only\n"
                            .getBytes(StandardCharsets.UTF_8)), callback);
                    return true;
                }
                return super.handle(request, response, callback);
            }
        });
        contexts.addHandler(healthContext());
        contexts.addHandler(rootRedirectContext());
        server.setStopTimeout(5000);
    }

    /** Replaces the client address and the secure flag by the forwarded ones when the peer is a trusted proxy. */
    static Request forwarded(NetworkPolicy policy, Request request) {
        ConnectionMetaData meta = request.getConnectionMetaData();
        String peer = Request.getRemoteAddr(request);
        if (!policy.isTrustedProxy(peer)) {
            return request;
        }
        NetworkPolicy.Client c = policy.resolve(peer, meta.isSecure(),
                request.getHeaders().get("X-Forwarded-For"), request.getHeaders().get("X-Forwarded-Proto"));
        byte[] literal = Cidr.literal(c.address());
        if (literal == null) {
            return request;
        }
        java.net.InetSocketAddress remote;
        try {
            remote = new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(literal), 0);
        } catch (java.net.UnknownHostException e) {
            return request;
        }
        java.net.InetSocketAddress forwardedRemote = remote;
        ConnectionMetaData wrapped = new ConnectionMetaData.Wrapper(meta) {
            @Override
            public java.net.SocketAddress getRemoteSocketAddress() {
                return forwardedRemote;
            }

            @Override
            public boolean isSecure() {
                return c.secure();
            }
        };
        return new Request.Wrapper(request) {
            @Override
            public ConnectionMetaData getConnectionMetaData() {
                return wrapped;
            }
        };
    }

    static ContextHandler healthContext() {
        ContextHandler health = new ContextHandler("/health");
        health.setAllowNullPathInContext(true);
        health.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
                response.write(true, java.nio.ByteBuffer.wrap(HEALTH_BODY.getBytes(StandardCharsets.UTF_8)), callback);
                return true;
            }
        });
        return health;
    }

    /** {@code GET /} goes to the web player; any other unmapped path stays a 404. Longer context paths win over it. */
    static ContextHandler rootRedirectContext() {
        ContextHandler root = new ContextHandler("/");
        root.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                String path = Request.getPathInContext(request);
                if (path != null && !path.isEmpty() && !"/".equals(path)) {
                    return false;
                }
                Response.sendRedirect(request, response, callback, "/app/");
                return true;
            }
        });
        return root;
    }

    public ContextHandlerCollection contexts() {
        return contexts;
    }

    public void start() throws Exception {
        server.start();
    }

    /** The actual bound port (differs from the configured one when that was 0). */
    public int port() {
        return connector.getLocalPort();
    }

    public void stop() throws Exception {
        server.stop();
    }
}
