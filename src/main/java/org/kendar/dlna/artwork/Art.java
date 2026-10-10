package org.kendar.dlna.artwork;

/**
 * A resolved image ready to serve. {@code etag} is a quoted strong validator that changes whenever the source
 * (or the thumbnail variant) changes.
 */
public record Art(byte[] data, String contentType, long lastModified, String etag, boolean thumbnail) {
}
