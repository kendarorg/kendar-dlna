package org.kendar.dlna.search;

import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Track;

/** One search result: a track or a directory. */
public sealed interface SearchHit {

    record TrackHit(Track track) implements SearchHit {
    }

    record FolderHit(Folder folder) implements SearchHit {
    }
}
