package org.kendar.dlna.search;

/** What a search can return. */
public enum Kind {
    TRACK("object.item.audioItem.musicTrack"),
    FOLDER("object.container.storageFolder");

    private final String upnpClass;

    Kind(String upnpClass) {
        this.upnpClass = upnpClass;
    }

    /** The UPnP class of results of this kind. */
    public String upnpClass() {
        return upnpClass;
    }
}
