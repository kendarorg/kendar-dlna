package org.kendar.dlna.dlna;

import java.util.List;

import org.jupnp.binding.annotations.UpnpAction;
import org.jupnp.binding.annotations.UpnpInputArgument;
import org.jupnp.binding.annotations.UpnpStateVariable;
import org.jupnp.binding.annotations.UpnpOutputArgument;
import org.jupnp.model.profile.RemoteClientInfo;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.support.contentdirectory.AbstractContentDirectoryService;
import org.jupnp.support.contentdirectory.ContentDirectoryErrorCode;
import org.jupnp.support.contentdirectory.ContentDirectoryException;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.SortCriterion;
import org.kendar.dlna.clients.ClientRequest;
import org.kendar.dlna.shared.ClientId;

/**
 * ContentDirectory:1 service backed by the catalog (Browse and Search).
 * <p>
 * GetSearchCapabilities returns the plan section 9 list; GetSortCapabilities lists {@link SortCriteria#CAPABILITIES}.
 * Without a {@code SortCriteria} the order is the one of settings.json; with one, Browse children and Search hits
 * are sorted before paging. SystemUpdateID follows the catalog snapshot version.
 */
public final class LibraryContentDirectory extends AbstractContentDirectoryService {

    static final List<String> SEARCH_CAPABILITIES = List.of("dc:title", "upnp:artist", "upnp:album", "upnp:genre",
            "dc:creator", "upnp:author", "upnp:class");

    private final BrowseEngine engine;

    /** Evented: pairs of object id and ContainerUpdateID of containers changed by the last commit. */
    // not rate-moderated: jUPnP drops (does not defer) a moderated value, which would lose the ids of a container change
    // made within the window of the previous one
    @UpnpStateVariable(sendEvents = true, defaultValue = "")
    private volatile String containerUpdateIDs = "";

    public LibraryContentDirectory(BrowseEngine engine) {
        super(SEARCH_CAPABILITIES, SortCriteria.CAPABILITIES);
        this.engine = engine;
    }

    @Override
    public synchronized UnsignedIntegerFourBytes getSystemUpdateID() {
        return new UnsignedIntegerFourBytes(engine.systemUpdateId() & 0xFFFFFFFFL);
    }

    public String getContainerUpdateIDs() {
        return containerUpdateIDs;
    }

    /**
     * Announces a committed catalog change to subscribed control points: the evented SystemUpdateID and the
     * ContainerUpdateIDs of the changed containers.
     */
    public void announce(String containerUpdateIdsValue) {
        this.containerUpdateIDs = containerUpdateIdsValue;
        // old value null: always a change, the new values are read through the getters. Two events race in jUPnP (the
        // second replaces the values of the first when the sender thread was not quicker), so the one that matters
        // most, ContainerUpdateIDs, goes last and is always delivered.
        propertyChangeSupport.firePropertyChange("SystemUpdateID", null, getSystemUpdateID());
        propertyChangeSupport.firePropertyChange("ContainerUpdateIDs", null, containerUpdateIdsValue);
    }

    /**
     * The UPnP Browse action. jUPnP passes the {@link RemoteClientInfo} (peer address and User-Agent) as the
     * trailing argument of an annotated action method; the inherited 6-argument action method cannot receive it,
     * so this variant is the one bound (see {@link ClientAwareServiceBinder}).
     */
    @UpnpAction(out = {
            @UpnpOutputArgument(name = "Result", stateVariable = "A_ARG_TYPE_Result", getterName = "getResult"),
            @UpnpOutputArgument(name = "NumberReturned", stateVariable = "A_ARG_TYPE_Count", getterName = "getCount"),
            @UpnpOutputArgument(name = "TotalMatches", stateVariable = "A_ARG_TYPE_Count",
                    getterName = "getTotalMatches"),
            @UpnpOutputArgument(name = "UpdateID", stateVariable = "A_ARG_TYPE_UpdateID",
                    getterName = "getContainerUpdateID") })
    public BrowseResult browse(
            @UpnpInputArgument(name = "ObjectID", aliases = "ContainerID") String objectId,
            @UpnpInputArgument(name = "BrowseFlag") String browseFlag,
            @UpnpInputArgument(name = "Filter") String filter,
            @UpnpInputArgument(name = "StartingIndex", stateVariable = "A_ARG_TYPE_Index") UnsignedIntegerFourBytes firstResult,
            @UpnpInputArgument(name = "RequestedCount", stateVariable = "A_ARG_TYPE_Count") UnsignedIntegerFourBytes maxResults,
            @UpnpInputArgument(name = "SortCriteria") String orderBy,
            RemoteClientInfo remoteClientInfo) throws ContentDirectoryException {
        try {
            BrowseFlag flag = BrowseFlag.valueOrNullOf(browseFlag);
            return engine.browse(objectId, flag, filter, firstResult.getValue(), maxResults.getValue(),
                    clientOf(remoteClientInfo), requestOf(remoteClientInfo), orderBy);
        } catch (ContentDirectoryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS, e.toString());
        }
    }

    /**
     * The Search action with the client info, so that results carry the res elements of the client profile (the
     * inherited 6-argument action cannot receive it; see {@link ClientAwareServiceBinder}).
     */
    @UpnpAction(out = {
            @UpnpOutputArgument(name = "Result", stateVariable = "A_ARG_TYPE_Result", getterName = "getResult"),
            @UpnpOutputArgument(name = "NumberReturned", stateVariable = "A_ARG_TYPE_Count", getterName = "getCount"),
            @UpnpOutputArgument(name = "TotalMatches", stateVariable = "A_ARG_TYPE_Count",
                    getterName = "getTotalMatches"),
            @UpnpOutputArgument(name = "UpdateID", stateVariable = "A_ARG_TYPE_UpdateID",
                    getterName = "getContainerUpdateID") })
    public BrowseResult search(
            @UpnpInputArgument(name = "ContainerID", stateVariable = "A_ARG_TYPE_ObjectID") String containerId,
            @UpnpInputArgument(name = "SearchCriteria") String searchCriteria,
            @UpnpInputArgument(name = "Filter") String filter,
            @UpnpInputArgument(name = "StartingIndex", stateVariable = "A_ARG_TYPE_Index") UnsignedIntegerFourBytes firstResult,
            @UpnpInputArgument(name = "RequestedCount", stateVariable = "A_ARG_TYPE_Count") UnsignedIntegerFourBytes maxResults,
            @UpnpInputArgument(name = "SortCriteria") String orderBy,
            RemoteClientInfo remoteClientInfo) throws ContentDirectoryException {
        try {
            return engine.search(containerId, searchCriteria, filter, firstResult.getValue(), maxResults.getValue(),
                    requestOf(remoteClientInfo), orderBy);
        } catch (ContentDirectoryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS, e.toString());
        }
    }

    private static String sortString(SortCriterion[] criteria) {
        if (criteria == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (SortCriterion c : criteria) {
            sb.append(sb.length() == 0 ? "" : ",").append(c.isAscending() ? '+' : '-').append(c.getPropertyName());
        }
        return sb.toString();
    }

    /** Address and headers of the caller, for profile matching; null when jUPnP gives none. */
    static ClientRequest requestOf(RemoteClientInfo info) {
        if (info == null) {
            return null;
        }
        String ip = info.getRemoteAddress() == null ? null : info.getRemoteAddress().getHostAddress();
        java.util.Map<String, List<String>> headers = new java.util.HashMap<>();
        if (info.getRequestHeaders() != null) {
            info.getRequestHeaders().forEach((k, v) -> headers.put(k, v));
        }
        return new ClientRequest(ip, headers);
    }

    /** Client identity (IP + User-Agent) from the action invocation; unknown when jUPnP gives none. */
    static ClientId clientOf(RemoteClientInfo info) {
        if (info == null) {
            return ClientId.UNKNOWN;
        }
        String ip = info.getRemoteAddress() == null ? null : info.getRemoteAddress().getHostAddress();
        return new ClientId(ip, info.getRequestUserAgent());
    }

    @Override
    public BrowseResult browse(String objectID, BrowseFlag browseFlag, String filter, long firstResult,
            long maxResults, SortCriterion[] orderby) throws ContentDirectoryException {
        try {
            return engine.browse(objectID, browseFlag, filter, firstResult, maxResults, ClientId.UNKNOWN,
                    ClientRequest.of(null, null), sortString(orderby));
        } catch (ContentDirectoryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS, e.toString());
        }
    }

    @Override
    public BrowseResult search(String containerId, String searchCriteria, String filter, long firstResult,
            long maxResults, SortCriterion[] orderBy) throws ContentDirectoryException {
        try {
            return engine.search(containerId, searchCriteria, filter, firstResult, maxResults, null,
                    sortString(orderBy));
        } catch (ContentDirectoryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS, e.toString());
        }
    }
}
