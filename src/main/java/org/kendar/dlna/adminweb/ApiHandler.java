package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;

/** Jetty adapter of the REST API: mounted at {@value #PATH_PREFIX}, delegates to {@link AdminApi}. */
public final class ApiHandler extends Handler.Abstract {

    public static final String PATH_PREFIX = "/api/v1";
    /** Largest accepted request body (art upload). */
    static final int MAX_BODY = 12 * 1024 * 1024;
    /** Largest accepted body of an audio file upload (held in memory). */
    static final int MAX_UPLOAD = 512 * 1024 * 1024;
    static final String UPLOAD_PATH = "/admin/folders/upload";

    private final AdminApi api;

    public ApiHandler(AdminApi api) {
        this.api = api;
    }

    public ContextHandler asContext() {
        ContextHandler ctx = new ContextHandler(PATH_PREFIX);
        ctx.setHandler(this);
        return ctx;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        ApiResponse out;
        try {
            out = api.handle(toApiRequest(request));
        } catch (TooLarge e) {
            out = ApiResponse.error(413, "too_large", "The request body is larger than the allowed size ("
                    + MAX_BODY / 1024 / 1024 + " MB, audio uploads " + MAX_UPLOAD / 1024 / 1024 + " MB)", null);
        } catch (IOException e) {
            out = ApiResponse.error(400, "bad_request", "Cannot read the request body", null);
        }
        response.setStatus(out.status());
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        out.headers().forEach((k, v) -> response.getHeaders().put(k, v));
        out.setCookies().forEach(c -> response.getHeaders().add(HttpHeader.SET_COOKIE, c));
        if (out.contentType() != null) {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, out.contentType());
        }
        if (out.streamed() != null) {
            return sendStreamed(out.streamed(), response, callback);
        }
        if (out.body().length == 0) {
            callback.succeeded();
        } else {
            response.write(true, ByteBuffer.wrap(out.body()), callback);
        }
        return true;
    }

    private static boolean sendStreamed(ApiResponse.Streamed s, Response response, Callback callback) {
        try (java.io.OutputStream sink = Content.Sink.asOutputStream(response)) {
            s.body().writeTo(s.start(), s.length(), sink);
        } catch (IOException | RuntimeException e) {
            callback.failed(e);   // client went away or the source failed: abort the connection
            return true;
        } finally {
            s.done().run();
        }
        callback.succeeded();
        return true;
    }

    static ApiRequest toApiRequest(Request request) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (HttpField f : request.getHeaders()) {
            headers.merge(f.getName().toLowerCase(Locale.ROOT), f.getValue(), (a, b) -> a + ", " + b);
        }
        byte[] body = new byte[0];
        int limit = Request.getPathInContext(request).equals(UPLOAD_PATH) ? MAX_UPLOAD : MAX_BODY;
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
            try (InputStream in = Content.Source.asInputStream(request)) {
                body = in.readNBytes(limit + 1);
            }
            if (body.length > limit) {
                throw new TooLarge();
            }
        }
        // the host applies X-Forwarded-* to the connection only when the peer is a configured trusted proxy
        boolean secure = request.getConnectionMetaData().isSecure();
        return new ApiRequest(request.getMethod(), Request.getPathInContext(request),
                ApiRequest.parseQuery(request.getHttpURI().getQuery()), headers, body, Request.getRemoteAddr(request),
                secure);
    }

    private static final class TooLarge extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
