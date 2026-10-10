package org.kendar.dlna.dlna;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;

/** Jetty adapter of the art endpoint. */
public final class ArtHandler extends Handler.Abstract {

    private final ArtEndpoint endpoint;

    public ArtHandler(ArtEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    /** A context handler mounting this endpoint at {@value ArtEndpoint#PATH_PREFIX}. */
    public ContextHandler asContext() {
        ContextHandler ctx = new ContextHandler(ArtEndpoint.PATH_PREFIX);
        ctx.setHandler(this);
        return ctx;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        ArtEndpoint.Response r = endpoint.handle(request.getMethod(), Request.getPathInContext(request),
                request.getHeaders().get(HttpHeader.IF_NONE_MATCH));
        response.setStatus(r.status());
        r.headers().forEach((k, v) -> response.getHeaders().put(k, v));
        if (r.body() == null) {
            callback.succeeded();
        } else {
            response.write(true, ByteBuffer.wrap(r.body()), callback);
        }
        return true;
    }
}
