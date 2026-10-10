package org.kendar.dlna.adminweb;

import java.nio.ByteBuffer;
import java.util.Optional;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;

/** Jetty adapter of the static web UI, mounted at {@value #PATH_PREFIX}. The UI itself is open; its data is not. */
public final class StaticHandler extends Handler.Abstract {

    /** The admin mount (LAN-only by the network policy); the same bundle is also served at {@value #APP_PREFIX}. */
    public static final String PATH_PREFIX = "/admin";
    /** The web player mount: reachable from any address (login required for the data). */
    public static final String APP_PREFIX = "/app";
    /** Vue compiles the string templates in the browser, which needs {@code unsafe-eval}. */
    static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    private final String prefix;

    public StaticHandler() {
        this(PATH_PREFIX);
    }

    public StaticHandler(String prefix) {
        this.prefix = prefix;
    }

    public ContextHandler asContext() {
        ContextHandler ctx = new ContextHandler(prefix);
        ctx.setAllowNullPathInContext(true);
        ctx.setHandler(this);
        return ctx;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String path = Request.getPathInContext(request);
        if (path == null || path.isEmpty()) {   // prefix -> prefix/ so that relative URLs resolve
            response.setStatus(302);
            response.getHeaders().put(HttpHeader.LOCATION, prefix + "/");
            callback.succeeded();
            return true;
        }
        String method = request.getMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            response.setStatus(405);
            response.getHeaders().put(HttpHeader.ALLOW, "GET, HEAD");
            callback.succeeded();
            return true;
        }
        Optional<StaticResources.Asset> asset = StaticResources.find(path);
        if (asset.isEmpty()) {
            response.setStatus(404);
            callback.succeeded();
            return true;
        }
        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, asset.get().contentType());
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-cache");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        if (asset.get().contentType().startsWith("text/html")) {
            response.getHeaders().put("Content-Security-Policy", CSP);
        }
        byte[] content = asset.get().content();
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, Integer.toString(content.length));
        if (method.equals("HEAD")) {
            callback.succeeded();
        } else {
            response.write(true, ByteBuffer.wrap(content), callback);
        }
        return true;
    }
}
