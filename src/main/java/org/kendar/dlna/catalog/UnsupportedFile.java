package org.kendar.dlna.catalog;

import org.kendar.dlna.shared.RootRef;

/** An audio-looking file that cannot be served (no decoder, unreadable tags, ...). Absent from DLNA. */
public record UnsupportedFile(RootRef ref, long size, String reason) {
}
