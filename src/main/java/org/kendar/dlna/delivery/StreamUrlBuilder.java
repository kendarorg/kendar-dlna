package org.kendar.dlna.delivery;

/** Builds absolute stream URLs; the container id is the browsing context of the item. */
public interface StreamUrlBuilder {

    /**
     * @param baseUrl     scheme, host and port, e.g. {@code http://192.168.1.10:8200} (no trailing slash)
     * @param containerId id of the container the item was browsed in
     * @param itemId      id of the item
     */
    String url(String baseUrl, String containerId, String itemId);

    /**
     * The URL of a transcoded form: the plain URL plus a variant segment ({@code mp3}, {@code mp3-256},
     * {@code lpcm}; see {@link StreamVariant#path()}). A native variant gives the plain URL.
     */
    default String url(String baseUrl, String containerId, String itemId, StreamVariant variant) {
        String plain = url(baseUrl, containerId, itemId);
        return variant == null || !variant.transcoded() ? plain : plain + "/" + variant.path();
    }
}
