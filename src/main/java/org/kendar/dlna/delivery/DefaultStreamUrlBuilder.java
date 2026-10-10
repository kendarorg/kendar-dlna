package org.kendar.dlna.delivery;

/** {@code <base>/stream/<containerId>/<itemId>[/<variant>]}. */
public final class DefaultStreamUrlBuilder implements StreamUrlBuilder {

    public static final String PATH_PREFIX = "/stream";

    @Override
    public String url(String baseUrl, String containerId, String itemId) {
        return baseUrl + PATH_PREFIX + "/" + containerId + "/" + itemId;
    }
}
