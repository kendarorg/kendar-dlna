package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

class ChunkedSinkTest {

    /** A response whose writes complete only when the test says so, as a renderer that reads at its own pace does. */
    private static final class ManualResponse {
        final List<Callback> pending = new ArrayList<>();
        final List<ByteBuffer> sent = new ArrayList<>();

        /** @param autoComplete true: every write completes at once (a renderer that reads everything) */
        ManualResponse(boolean autoComplete) {
            response = (Response) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Response.class}, (proxy, method, args) -> {
                        if (method.getName().equals("write") && args.length == 3) {
                            sent.add((ByteBuffer) args[1]);
                            if (autoComplete) {
                                ((Callback) args[2]).succeeded();
                            } else {
                                pending.add((Callback) args[2]);
                            }
                        }
                        return null;
                    });
        }

        final Response response;
    }

    @Test
    void writesPassWhileTheRendererReads() throws IOException {
        ManualResponse r = new ManualResponse(true);
        try (StreamHandler.ChunkedSink sink = new StreamHandler.ChunkedSink(r.response, 5_000)) {
            sink.write(new byte[] {1, 2, 3}, 0, 3);
            sink.write(new byte[] {4, 5}, 0, 2);
        }
        assertThat(r.sent).hasSize(3);   // two chunks and the final empty one
        assertThat(r.sent.get(0).remaining()).isEqualTo(3);
    }

    @Test
    void aRendererThatStopsReadingFailsTheWriteAfterTheStallWindow() throws IOException {
        ManualResponse r = new ManualResponse(false);
        StreamHandler.ChunkedSink sink = new StreamHandler.ChunkedSink(r.response, 200);
        sink.write(new byte[] {1}, 0, 1);   // taken by nobody: stays pending
        assertThatThrownBy(() -> sink.write(new byte[] {2}, 0, 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stopped reading");
        // once broken, later calls fail at once instead of waiting another window
        assertThatThrownBy(() -> sink.write(new byte[] {3}, 0, 1)).isInstanceOf(IOException.class);
        sink.close();   // must not wait either
        assertThat(r.sent).hasSize(1);
    }

    @Test
    void aFailedChunkSurfacesAsAnIOException() throws IOException {
        ManualResponse r = new ManualResponse(false);
        StreamHandler.ChunkedSink sink = new StreamHandler.ChunkedSink(r.response, 5_000);
        sink.write(new byte[] {1}, 0, 1);
        r.pending.get(0).failed(new RuntimeException("connection reset"));
        assertThatThrownBy(() -> sink.write(new byte[] {2}, 0, 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("failed");
    }
}
