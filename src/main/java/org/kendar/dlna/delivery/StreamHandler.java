package org.kendar.dlna.delivery;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;

/** Jetty adapter of the stream endpoint: Range/206, HEAD, exact Content-Length, DLNA headers. */
public final class StreamHandler extends Handler.Abstract {

    /** How long a renderer may leave a chunk unread before the stream is aborted. */
    static final long STALL_MILLIS = 60_000L;

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
        try (OutputStream out = new ChunkedSink(response, STALL_MILLIS)) {
            r.body().writeTo(plan.start(), plan.length(), out);
        } catch (IOException | RuntimeException e) {
            callback.failed(e);   // client went away, stalled, or the source failed: abort the connection
            return true;
        } finally {
            r.lease().close();   // stream activity ends
            open.close();
        }
        callback.succeeded();
        return true;
    }

    /**
     * Sends the body one chunk at a time: the next chunk is queued only once the previous one has been taken by the
     * connection. A renderer that stops reading leaves a chunk pending, and after {@code stallMillis} the write fails,
     * so the stream is released instead of blocking its thread and its transcode indefinitely.
     */
    static final class ChunkedSink extends OutputStream {

        private final Response response;
        private final long stallMillis;
        private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
        private IOException broken;
        private boolean closed;

        ChunkedSink(Response response, long stallMillis) {
            this.response = response;
            this.stallMillis = stallMillis;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return;
            }
            awaitPending();
            send(false, ByteBuffer.wrap(Arrays.copyOfRange(b, off, off + len)));
        }

        @Override
        public void close() throws IOException {
            if (closed || broken != null) {
                return;
            }
            closed = true;
            awaitPending();
            send(true, ByteBuffer.allocate(0));
            awaitPending();
        }

        private void send(boolean last, ByteBuffer content) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            pending = done;
            response.write(last, content, new Callback() {
                @Override
                public void succeeded() {
                    done.complete(null);
                }

                @Override
                public void failed(Throwable x) {
                    done.completeExceptionally(x);
                }
            });
        }

        private void awaitPending() throws IOException {
            if (broken != null) {
                throw broken;
            }
            try {
                pending.get(stallMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                broken = new IOException("Renderer stopped reading for " + stallMillis / 1000 + " s", e);
            } catch (ExecutionException e) {
                broken = new IOException("Write to renderer failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broken = new IOException("Interrupted while sending", e);
            }
            if (broken != null) {
                throw broken;
            }
        }
    }
}
