package org.kendar.dlna.delivery;

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;

/** Jetty adapter of the stream endpoint: Range/206, HEAD, exact Content-Length, DLNA headers. */
public final class StreamHandler extends Handler.Abstract {

    private final StreamResolver resolver;
    private final ActiveStreams active;

    public StreamHandler(StreamResolver resolver) {
        this(resolver, new ActiveStreams());
    }

    public StreamHandler(StreamResolver resolver, ActiveStreams active) {
        this.resolver = resolver;
        this.active = active;
    }

    /** A context handler mounting this endpoint at {@value DefaultStreamUrlBuilder#PATH_PREFIX}. */
    public ContextHandler asContext() {
        ContextHandler ctx = new ContextHandler(DefaultStreamUrlBuilder.PATH_PREFIX);
        ctx.setHandler(this);
        return ctx;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String range = request.getHeaders().get(HttpHeader.RANGE);
        String client = Request.getRemoteAddr(request);
        StreamResolver.Resolved r = resolver.resolve(request.getMethod(), Request.getPathInContext(request), range,
                client);
        StreamPlan plan = r.plan();
        response.setStatus(plan.status());
        plan.headers().forEach((k, v) -> response.getHeaders().put(k, v));
        if (r.body() == null) {
            r.lease().close();
            callback.succeeded();
            return true;
        }
        ActiveStreams.Handle open = active.openForPath(client, Request.getPathInContext(request));
        try (OutputStream out = Content.Sink.asOutputStream(response)) {
            r.body().writeTo(plan.start(), plan.length(), out);
        } catch (IOException | RuntimeException e) {
            callback.failed(e);   // client went away or the source failed: abort the connection
            return true;
        } finally {
            r.lease().close();   // stream activity ends
            open.close();
        }
        callback.succeeded();
        return true;
    }
}
