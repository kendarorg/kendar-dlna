package org.kendar.dlna.curation;

import java.nio.file.Path;

import org.kendar.dlna.shared.RootRef;

/** Port: maps a library location to its file on disk. */
public interface LibraryFiles {

    /** @return the file, or {@code null} when the root is unknown or the reference escapes the root */
    Path resolve(RootRef ref);
}
