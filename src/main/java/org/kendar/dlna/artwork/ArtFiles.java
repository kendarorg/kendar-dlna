package org.kendar.dlna.artwork;

import java.nio.file.Path;

import org.kendar.dlna.shared.RootRef;

/** Port: maps a library location to its file on disk. */
public interface ArtFiles {

    /** @return the file, or {@code null} when the root is unknown */
    Path resolve(RootRef ref);
}
