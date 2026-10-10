package org.kendar.dlna.catalog;

import org.kendar.dlna.shared.RootRef;

/** A direct child of a directory: either a subdirectory or a track. */
public sealed interface Child {

    RootRef ref();

    record FolderChild(Folder folder) implements Child {
        @Override
        public RootRef ref() {
            return folder.ref();
        }
    }

    record TrackChild(Track track) implements Child {
        @Override
        public RootRef ref() {
            return track.ref();
        }
    }
}
