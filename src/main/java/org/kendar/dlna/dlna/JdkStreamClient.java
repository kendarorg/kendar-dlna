package org.kendar.dlna.dlna;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.jupnp.http.Headers;
import org.jupnp.model.message.StreamRequestMessage;
import org.jupnp.model.message.StreamResponseMessage;
import org.jupnp.model.message.UpnpHeaders;
import org.jupnp.model.message.UpnpMessage;
import org.jupnp.model.message.UpnpRequest;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.message.header.UpnpHeader;
import org.jupnp.transport.impl.jetty.StreamClientConfigurationImpl;
import org.jupnp.transport.spi.AbstractStreamClient;

/**
 * jUPnP stream client on the JDK HTTP client (used for GENA event delivery and device fetches).
 * Replaces jUPnP's bundled Jetty 9 client, which is incompatible with Jetty 12.
 */
public final class JdkStreamClient extends AbstractStreamClient<StreamClientConfigurationImpl, HttpRequest> {

    private static final Set<String> RESTRICTED = Set.of("host", "connection", "content-length", "expect", "upgrade");

    private final StreamClientConfigurationImpl configuration;
    private final HttpClient client;

    public JdkStreamClient(StreamClientConfigurationImpl configuration) {
        this.configuration = configuration;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(configuration.getTimeoutSeconds() + 5L))
                .build();
    }

    @Override
    public StreamClientConfigurationImpl getConfiguration() {
        return configuration;
    }

    @Override
    protected HttpRequest createRequest(StreamRequestMessage message) {
        UpnpRequest op = message.getOperation();
        try {
            HttpRequest.BodyPublisher body = switch (op.getMethod()) {
                case POST, NOTIFY -> message.getBodyType() == UpnpMessage.BodyType.STRING
                        ? HttpRequest.BodyPublishers.ofString(message.getBodyString())
                        : HttpRequest.BodyPublishers.ofByteArray(message.getBodyBytes());
                default -> HttpRequest.BodyPublishers.noBody();
            };
            HttpRequest.Builder b = HttpRequest.newBuilder(op.getURI())
                    .method(op.getHttpMethodName(), body)
                    .timeout(Duration.ofSeconds(configuration.getTimeoutSeconds()));
            if (!message.getHeaders().containsKey(UpnpHeader.Type.USER_AGENT)) {
                b.header("User-Agent",
                        configuration.getUserAgentValue(message.getUdaMajorVersion(), message.getUdaMinorVersion()));
            }
            for (Map.Entry<String, List<String>> e : message.getHeaders().entrySet()) {
                if (RESTRICTED.contains(e.getKey().toLowerCase())) {
                    continue;
                }
                for (String v : e.getValue()) {
                    b.header(e.getKey(), v);
                }
            }
            return b.build();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected Callable<StreamResponseMessage> createCallable(StreamRequestMessage message, HttpRequest request) {
        return () -> {
            HttpResponse<byte[]> r = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            StreamResponseMessage out = new StreamResponseMessage(new UpnpResponse(r.statusCode(), ""));
            Headers headers = new Headers();
            r.headers().map().forEach((k, vs) -> vs.forEach(v -> headers.add(k, v)));
            out.setHeaders(new UpnpHeaders(headers));
            byte[] bytes = r.body();
            if (bytes != null && bytes.length > 0) {
                out.setBodyCharacters(bytes);
            }
            return out;
        };
    }

    @Override
    protected void abort(HttpRequest request) {
        // aborted through the request timeout / thread interruption
    }

    @Override
    protected boolean logExecutionException(Throwable t) {
        return false;
    }

    @Override
    public void stop() {
        client.close();
    }
}
