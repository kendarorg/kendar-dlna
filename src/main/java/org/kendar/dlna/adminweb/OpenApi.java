package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.io.InputStream;

/** The hand-written OpenAPI description, shipped as the resource {@code /openapi/openapi.yaml}. */
final class OpenApi {

    static final String RESOURCE = "/openapi/openapi.yaml";

    private static volatile byte[] cached;

    private OpenApi() {
    }

    static ApiResponse response() {
        byte[] data = cached;
        if (data == null) {
            try (InputStream in = OpenApi.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    return ApiResponse.error(404, "not_found", "The OpenAPI description is not packaged", null);
                }
                data = in.readAllBytes();
                cached = data;
            } catch (IOException e) {
                return ApiResponse.error(500, "io_error", "Cannot read the OpenAPI description", null);
            }
        }
        return ApiResponse.bytes("application/yaml; charset=utf-8", data);
    }
}
