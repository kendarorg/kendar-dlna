package org.kendar.dlna.dlna;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.jupnp.support.contentdirectory.ContentDirectoryErrorCode;
import org.jupnp.support.contentdirectory.ContentDirectoryException;
import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLAttribute;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.DIDLObject;
import org.jupnp.support.model.PersonWithRole;
import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.Res;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.container.MusicAlbum;
import org.jupnp.support.model.container.MusicArtist;
import org.jupnp.support.model.container.MusicGenre;
import org.jupnp.support.model.container.PlaylistContainer;
import org.jupnp.support.model.container.StorageFolder;
import org.jupnp.support.model.item.MusicTrack;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.CatalogNavigation;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.ContainerKeys;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.ResolvedPlaylist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.clients.ClientProfile;
import org.kendar.dlna.clients.ClientProfileResolver;
import org.kendar.dlna.clients.ClientProfiles;
import org.kendar.dlna.clients.ClientRequest;
import org.kendar.dlna.delivery.ResourceOffers;
import org.kendar.dlna.delivery.StreamUrlBuilder;
import org.kendar.dlna.search.Query;
import org.kendar.dlna.search.SearchEngine;
import org.kendar.dlna.search.SearchHit;
import org.kendar.dlna.search.SearchScope;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shuffle.RandomSnapshot;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.views.ViewNode;
import org.kendar.dlna.views.Views;

/**
 * ContentDirectory Browse over the catalog: Root(0) -> Folders (library roots, subdirectories, tracks, plus per
 * directory the Random N and view containers) and Playlists. Pure (no network): builds the DIDL for
 * BrowseDirectChildren / BrowseMetadata with exact paging.
 */
public final class BrowseEngine {

    private static final String UNKNOWN_PARENT = "-1";
    /** UPnP ContentDirectory error codes for Search. */
    static final int SEARCH_CRITERIA_INVALID = 708;
    static final int SEARCH_NO_SUCH_CONTAINER = 710;

    private final SnapshotHolder holder;
    private final StreamUrlBuilder urls;
    private final Supplier<String> baseUrl;
    private final ArtworkService artwork;
    private final Shuffle shuffle;
    private final Views views;
    private final ResourceOffers offers;
    private final ClientProfileResolver clients;

    /** With default Random N / views settings. @param baseUrl supplies {@code http://host:port} used in URLs */
    public BrowseEngine(SnapshotHolder holder, StreamUrlBuilder urls, Supplier<String> baseUrl,
            ArtworkService artwork) {
        this(holder, urls, baseUrl, artwork, new Shuffle(holder::current, Clock.systemUTC(), Shuffle.DEFAULT_TTL),
                new Views(holder::current));
    }

    public BrowseEngine(SnapshotHolder holder, StreamUrlBuilder urls, Supplier<String> baseUrl,
            ArtworkService artwork, Shuffle shuffle, Views views) {
        this(holder, urls, baseUrl, artwork, shuffle, views, ResourceOffers.nativeOnly(),
                ClientProfiles.defaultOnly());
    }

    /**
     *  offers  the res elements per track and client profile (transcoding on or off)
     * @param clients picks the renderer profile of a client
     */
    public BrowseEngine(SnapshotHolder holder, StreamUrlBuilder urls, Supplier<String> baseUrl,
            ArtworkService artwork, Shuffle shuffle, Views views, ResourceOffers offers,
            ClientProfileResolver clients) {
        this.offers = offers;
        this.clients = clients;
        this.artwork = artwork;
        this.holder = holder;
        this.urls = urls;
        this.baseUrl = baseUrl;
        this.shuffle = shuffle;
        this.views = views;
    }

    /** One browsable object. */
    private sealed interface Node {
        record Root() implements Node {
        }

        record Folders() implements Node {
        }

        record Playlists() implements Node {
        }

        record FolderNode(Folder folder) implements Node {
        }

        record TrackNode(Track track) implements Node {
        }

        record PlaylistNode(ResolvedPlaylist playlist) implements Node {
        }

        record RandomNode(RandomSnapshot snapshot) implements Node {
        }

        record ViewGroupNode(ViewNode node) implements Node {
        }

        /** A track listed inside a virtual container. */
        record ContextTrackNode(String context, Track track) implements Node {
        }
    }

    /** The current catalog snapshot. */
    public Snapshot snapshot() {
        return holder.current();
    }

    /** The SystemUpdateID: the current snapshot version. */
    public long systemUpdateId() {
        return holder.current().updateId();
    }

    /**
     * The UpdateID reported by Browse: the container's own ContainerUpdateID (Random N and view containers follow
     * their directory's virtual containers), the SystemUpdateID for the root and for items.
     */
    private static long updateIdOf(Snapshot snap, Node node) {
        return switch (node) {
            case Node.Folders f -> snap.containerUpdateId(ContainerKeys.FOLDERS);
            case Node.Playlists p -> snap.containerUpdateId(ContainerKeys.PLAYLISTS);
            case Node.FolderNode f -> snap.containerUpdateId(ContainerKeys.folder(f.folder().id()));
            case Node.PlaylistNode p -> snap.containerUpdateId(ContainerKeys.playlist(p.playlist().id()));
            case Node.RandomNode r -> snap.containerUpdateId(ContainerKeys.virtual(r.snapshot().directoryId()));
            case Node.ViewGroupNode v -> {
                String[] parts = v.node().id().split("\\.");
                yield parts.length > 1 ? snap.containerUpdateId(ContainerKeys.virtual(parts[1])) : snap.updateId();
            }
            case Node.Root r -> snap.updateId();
            case Node.TrackNode t -> snap.updateId();
            case Node.ContextTrackNode t -> snap.updateId();
        };
    }

    /** Browse with the full property set for an unidentified client. */
    public BrowseResult browse(String objectId, BrowseFlag flag, long startingIndex, long count)
            throws ContentDirectoryException {
        return browse(objectId, flag, "*", startingIndex, count);
    }

    public BrowseResult browse(String objectId, BrowseFlag flag, String filterArg, long startingIndex, long count)
            throws ContentDirectoryException {
        return browse(objectId, flag, filterArg, startingIndex, count, ClientId.UNKNOWN);
    }

    /**
     * @param filter the ContentDirectory Filter argument (blank or * = everything)
     * @param count 0 means "all remaining"; a negative or out-of-range startingIndex yields an empty page
     *              (TotalMatches stays exact)
     * @param client identity used for per-client Random N snapshots
     * @throws ContentDirectoryException NO_SUCH_OBJECT for unknown ids
     */
    public BrowseResult browse(String objectId, BrowseFlag flag, String filterArg, long startingIndex, long count,
            ClientId client) throws ContentDirectoryException {
        return browse(objectId, flag, filterArg, startingIndex, count, client,
                ClientRequest.of(client.ip(), client.userAgent()));
    }

    /**
     * Browse for an identified client: its profile (User-Agent, headers, address) decides which res elements each
     * item gets.
     */
    public BrowseResult browse(String objectId, BrowseFlag flag, String filterArg, long startingIndex, long count,
            ClientId client, ClientRequest request) throws ContentDirectoryException {
        ClientProfile profile = clients.resolve(request);
        DidlFilter filter = DidlFilter.parse(filterArg);
        Snapshot snap = holder.current();
        Node node = resolve(snap, objectId).orElseThrow(() -> new ContentDirectoryException(
                ContentDirectoryErrorCode.NO_SUCH_OBJECT, "No such object: " + objectId));
        String base = baseUrl.get();
        DIDLContent didl = new DIDLContent();
        long returned;
        long total;
        if (flag == BrowseFlag.METADATA) {
            add(didl, snap, node, parentId(node), base, filter, profile);
            returned = 1;
            total = 1;
        } else {
            if (node instanceof Node.TrackNode || node instanceof Node.ContextTrackNode) {
                throw new ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS,
                        "Not a container: " + objectId);
            }
            List<Node> kids = children(snap, node, client);
            total = kids.size();
            int from = (int) Math.min(Math.max(startingIndex, 0), total);
            long end = count <= 0 || count >= total - from ? total : from + count;   // overflow-safe
            String parent = idOf(node);
            for (Node kid : kids.subList(from, (int) end)) {
                add(didl, snap, kid, parent, base, filter, profile);
            }
            returned = end - from;
        }
        try {
            return new BrowseResult(new DIDLParser().generate(didl), returned, total, updateIdOf(snap, node));
        } catch (Exception e) {
            throw new ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS, e.toString());
        }
    }

    /**
     * ContentDirectory Search: parses the criteria, scopes to the container's subtree and pages the hits (tracks
     * and directories, in display order) with the same DIDL mapping as Browse. Random N and view containers
     * (and their items) are never hits, and are not valid search scopes.
     *
     * @param count 0 means "all remaining"; TotalMatches is always exact
     * @throws ContentDirectoryException 708 for malformed criteria, 710 for an unknown or non-container scope
     */
    public BrowseResult search(String containerId, String criteria, String filterArg, long startingIndex,
            long count) throws ContentDirectoryException {
        return search(containerId, criteria, filterArg, startingIndex, count, null);
    }

    /** Search for an identified client (null request = the default profile). */
    public BrowseResult search(String containerId, String criteria, String filterArg, long startingIndex,
            long count, ClientRequest request) throws ContentDirectoryException {
        ClientProfile profile = clients.resolve(request);
        Query query;
        try {
            query = SearchCriteriaParser.parse(criteria);
        } catch (InvalidSearchCriteriaException e) {
            throw new ContentDirectoryException(SEARCH_CRITERIA_INVALID, "Invalid search criteria: " + e.getMessage());
        }
        DidlFilter filter = DidlFilter.parse(filterArg);
        Snapshot snap = holder.current();
        SearchScope scope = searchScope(snap, containerId);
        List<SearchHit> hits = SearchEngine.search(snap, scope, query);
        long total = hits.size();
        int from = (int) Math.min(Math.max(startingIndex, 0), total);
        long end = count <= 0 || count >= total - from ? total : from + count;   // overflow-safe
        String base = baseUrl.get();
        DIDLContent didl = new DIDLContent();
        for (SearchHit hit : hits.subList(from, (int) end)) {
            Node node = switch (hit) {
                case SearchHit.TrackHit h -> new Node.TrackNode(h.track());
                case SearchHit.FolderHit h -> new Node.FolderNode(h.folder());
            };
            add(didl, snap, node, parentId(node), base, filter, profile);
        }
        try {
            return new BrowseResult(new DIDLParser().generate(didl), end - from, total, snap.updateId());
        } catch (Exception e) {
            throw new ContentDirectoryException(ContentDirectoryErrorCode.CANNOT_PROCESS, e.toString());
        }
    }

    private static SearchScope searchScope(Snapshot snap, String containerId) throws ContentDirectoryException {
        if (ObjectIds.ROOT.equals(containerId) || ObjectIds.FOLDERS.equals(containerId)) {
            return SearchScope.LIBRARY;
        }
        Optional<Folder> folder = ObjectIds.folderId(containerId).flatMap(snap::folderById);
        if (folder.isEmpty()) {
            throw new ContentDirectoryException(SEARCH_NO_SUCH_CONTAINER, "No such container: " + containerId);
        }
        return SearchScope.below(folder.get().ref());
    }

    // ---- resolving ids ------------------------------------------------------------------------------------------

    private Optional<Node> resolve(Snapshot snap, String objectId) {
        if (ObjectIds.ROOT.equals(objectId)) {
            return Optional.of(new Node.Root());
        }
        if (ObjectIds.FOLDERS.equals(objectId)) {
            return Optional.of(new Node.Folders());
        }
        if (ObjectIds.PLAYLISTS.equals(objectId)) {
            return Optional.of(new Node.Playlists());
        }
        Optional<Node> folder = ObjectIds.folderId(objectId).flatMap(snap::folderById).map(Node.FolderNode::new);
        if (folder.isPresent()) {
            return folder;
        }
        Optional<Node> track = ObjectIds.trackId(objectId).flatMap(snap::trackById).map(Node.TrackNode::new);
        if (track.isPresent()) {
            return track;
        }
        Optional<String> ctx = ObjectIds.containerContext(objectId);
        if (ctx.isPresent()) {
            return resolveContainer(snap, ctx.get());
        }
        Optional<ObjectIds.ContextItem> item = ObjectIds.contextItem(objectId);
        if (item.isPresent() && resolveContainer(snap, item.get().context()).isPresent()) {
            return snap.trackById(item.get().trackId()).map(t -> new Node.ContextTrackNode(item.get().context(), t));
        }
        return Optional.empty();
    }

    private Optional<Node> resolveContainer(Snapshot snap, String ctx) {
        if (ctx.startsWith(CatalogNavigation.PLAYLIST_PREFIX)) {
            return snap.playlist(ctx.substring(CatalogNavigation.PLAYLIST_PREFIX.length()))
                    .map(Node.PlaylistNode::new);
        }
        if (ctx.startsWith(Shuffle.CONTEXT_PREFIX)) {
            return shuffle.find(ctx).map(Node.RandomNode::new);
        }
        if (ctx.startsWith(Views.CONTEXT_PREFIX) || ctx.startsWith(Views.RECENT_PREFIX)) {
            return views.node(snap, ctx).map(Node.ViewGroupNode::new);
        }
        return Optional.empty();
    }

    private List<Node> children(Snapshot snap, Node node, ClientId client) {
        return switch (node) {
            case Node.Root r -> List.of(new Node.Folders(), new Node.Playlists());
            case Node.Folders f -> snap.roots().stream().<Node>map(Node.FolderNode::new).toList();
            case Node.Playlists p -> snap.playlists().stream().<Node>map(Node.PlaylistNode::new).toList();
            case Node.FolderNode f -> folderChildren(snap, f.folder(), client);
            case Node.PlaylistNode p -> contextTracks(CatalogNavigation.playlistContext(p.playlist().id()),
                    p.playlist().tracks());
            case Node.RandomNode r -> contextTracks(r.snapshot().contextId(), r.snapshot().tracks(snap));
            case Node.ViewGroupNode v -> {
                List<Node> out = new ArrayList<>();
                v.node().children().forEach(c -> out.add(new Node.ViewGroupNode(c)));
                out.addAll(contextTracks(v.node().id(), v.node().tracks()));
                yield out;
            }
            case Node.TrackNode t -> List.of();
            case Node.ContextTrackNode t -> List.of();
        };
    }

    private List<Node> folderChildren(Snapshot snap, Folder folder, ClientId client) {
        List<Node> out = new ArrayList<>();
        if (folder.settings().random() > 0) {
            out.add(new Node.RandomNode(shuffle.open(client, folder)));
        }
        views.recentRoot(snap, folder).ifPresent(r -> out.add(new Node.ViewGroupNode(r)));
        views.roots(snap, folder).forEach(v -> out.add(new Node.ViewGroupNode(v)));
        for (Child c : snap.children(folder.ref())) {
            out.add(switch (c) {
                case Child.FolderChild fc -> new Node.FolderNode(fc.folder());
                case Child.TrackChild tc -> new Node.TrackNode(tc.track());
            });
        }
        return out;
    }

    private static List<Node> contextTracks(String context, List<Track> tracks) {
        return tracks.stream().<Node>map(t -> new Node.ContextTrackNode(context, t)).toList();
    }

    /** Number of entries a folder lists: Random N, views and the real children. */
    private static int folderChildCount(Snapshot snap, Folder folder) {
        DirectorySettings s = folder.settings();
        return (s.random() > 0 ? 1 : 0) + (s.recent() > 0 ? 1 : 0) + s.views().size() + snap.children(folder.ref()).size();
    }

    private static String idOf(Node node) {
        return switch (node) {
            case Node.Root r -> ObjectIds.ROOT;
            case Node.Folders f -> ObjectIds.FOLDERS;
            case Node.Playlists p -> ObjectIds.PLAYLISTS;
            case Node.FolderNode f -> ObjectIds.folder(f.folder());
            case Node.TrackNode t -> ObjectIds.track(t.track());
            case Node.PlaylistNode p -> ObjectIds.container(CatalogNavigation.playlistContext(p.playlist().id()));
            case Node.RandomNode r -> ObjectIds.container(r.snapshot().contextId());
            case Node.ViewGroupNode v -> ObjectIds.container(v.node().id());
            case Node.ContextTrackNode t -> ObjectIds.contextItem(t.context(), t.track());
        };
    }

    private static String parentId(Node node) {
        return switch (node) {
            case Node.Root r -> UNKNOWN_PARENT;
            case Node.Folders f -> ObjectIds.ROOT;
            case Node.Playlists p -> ObjectIds.ROOT;
            case Node.FolderNode f -> f.folder().isRoot() ? ObjectIds.FOLDERS
                    : ObjectIds.folderOf(Folder.idOf(f.folder().ref().parent()));
            case Node.TrackNode t -> ObjectIds.folderOf(Folder.idOf(t.track().parentRef()));
            case Node.PlaylistNode p -> ObjectIds.PLAYLISTS;
            case Node.RandomNode r -> ObjectIds.folderOf(r.snapshot().directoryId());
            case Node.ViewGroupNode v -> v.node().parentId() != null ? ObjectIds.container(v.node().parentId())
                    : ObjectIds.folderOf(directoryOfViewContext(v.node().id()));
            case Node.ContextTrackNode t -> ObjectIds.container(t.context());
        };
    }

    /** The directory id embedded in a {@code v.<dirId>.<view>...} context. */
    private static String directoryOfViewContext(String context) {
        return context.split("\\.")[1];
    }

    // ---- DIDL mapping -------------------------------------------------------------------------------------------

    private void add(DIDLContent didl, Snapshot snap, Node node, String parentId, String base, DidlFilter filter,
            ClientProfile profile) {
        switch (node) {
            case Node.Root r -> didl.addContainer(
                    container(ObjectIds.ROOT, parentId, "Root", 2, filter));
            case Node.Folders f -> didl.addContainer(
                    container(ObjectIds.FOLDERS, parentId, "Folders", snap.roots().size(), filter));
            case Node.Playlists p -> didl.addContainer(
                    container(ObjectIds.PLAYLISTS, parentId, "Playlists", snap.playlists().size(), filter));
            case Node.FolderNode f -> {
                Folder folder = f.folder();
                Container c = folder.isVirtual() ? virtualAlbum(snap, folder, parentId, filter)
                        : container(ObjectIds.folder(folder), parentId, DisplayNames.folderTitle(folder),
                                folderChildCount(snap, folder), filter);
                if (filter.wants("upnp:albumArtURI") && artwork.folderHasArt(snap, folder)) {
                    addAlbumArt(c, base + ArtEndpoint.folderPath(folder.id(), true), filter);
                }
                didl.addContainer(c);
            }
            case Node.TrackNode t -> didl.addItem(track(snap, t.track(), ObjectIds.track(t.track()), parentId,
                    Folder.idOf(t.track().parentRef()), base, filter, profile));
            case Node.PlaylistNode p -> {
                PlaylistContainer c = new PlaylistContainer(idOf(node), parentId, p.playlist().name(), null,
                        childCount(p.playlist().tracks().size(), filter));
                c.setRestricted(true);
                didl.addContainer(c);
            }
            case Node.RandomNode r -> didl.addContainer(container(idOf(node), parentId,
                    Shuffle.title(r.snapshot().requestedSize()), r.snapshot().tracks(snap).size(), filter));
            case Node.ViewGroupNode v -> didl.addContainer(viewContainer(snap, v.node(), parentId, base, filter));
            case Node.ContextTrackNode t -> didl.addItem(track(snap, t.track(),
                    ObjectIds.contextItem(t.context(), t.track()), parentId, t.context(), base, filter, profile));
        }
    }

    /** The container of a cue sheet / chaptered file: an album whose items are its sections. */
    private static Container virtualAlbum(Snapshot snap, Folder folder, String parentId, DidlFilter filter) {
        String artist = null;
        for (Child c : snap.children(folder.ref())) {
            if (c instanceof Child.TrackChild t) {
                artist = firstNonBlank(t.track().tags().albumArtist(), t.track().tags().artist());
                break;
            }
        }
        MusicAlbum album = new MusicAlbum(ObjectIds.folder(folder), parentId, DisplayNames.folderTitle(folder),
                artist, childCount(folderChildCount(snap, folder), filter));
        if (artist != null && filter.wants("upnp:artist")) {
            album.setArtists(new PersonWithRole[] { new PersonWithRole(artist) });
        }
        album.setRestricted(true);
        return album;
    }

    private Container viewContainer(Snapshot snap, ViewNode n, String parentId, String base, DidlFilter filter) {
        String id = ObjectIds.container(n.id());
        Integer count = childCount(n.childCount(), filter);
        Container c;
        switch (n.type()) {
            case ALBUM -> {
                MusicAlbum album = new MusicAlbum(id, parentId, n.title(), n.artist(), count);
                if (n.artist() != null && filter.wants("upnp:artist")) {
                    album.setArtists(new PersonWithRole[] { new PersonWithRole(n.artist()) });
                }
                if (n.year() != null && n.year() > 0 && filter.wants("dc:date")) {
                    album.addProperty(new DIDLObject.Property.DC.DATE(
                            String.format(Locale.ROOT, "%04d-01-01", n.year())));
                }
                c = album;
            }
            case ARTIST -> c = new MusicArtist(id, parentId, n.title(), null, count);
            case GENRE -> c = new MusicGenre(id, parentId, n.title(), null, count);
            default -> c = new StorageFolder(id, parentId, n.title(), null, count, null);
        }
        c.setRestricted(true);
        boolean art = n.type() == ViewNode.Type.ALBUM || n.type() == ViewNode.Type.ARTIST
                || n.type() == ViewNode.Type.GENRE;
        if (art && n.artTrack() != null && filter.wants("upnp:albumArtURI") && artwork.trackHasArt(snap, n.artTrack())) {
            addAlbumArt(c, base + artPath(snap, n.artTrack()), filter);
        }
        return c;
    }

    private static Integer childCount(int count, DidlFilter filter) {
        return filter.wantsContainerAttribute("childCount") ? Integer.valueOf(count) : null;
    }

    private static StorageFolder container(String id, String parentId, String title, int childCount,
            DidlFilter filter) {
        StorageFolder c = new StorageFolder(id, parentId, title, null, childCount(childCount, filter), null);
        c.setRestricted(true);
        return c;
    }

    private static void addAlbumArt(DIDLObject object, String url, DidlFilter filter) {
        List<DIDLObject.Property<DIDLAttribute>> attrs = new ArrayList<>();
        if (filter.wantsAttribute("upnp:albumArtURI", "dlna:profileID")) {
            attrs.add(new DIDLObject.Property.DLNA.PROFILE_ID(new DIDLAttribute(
                    DIDLObject.Property.DLNA.NAMESPACE.URI, "dlna", "JPEG_TN")));
        }
        object.addProperty(new DIDLObject.Property.UPNP.ALBUM_ART_URI(URI.create(url), attrs));
    }

    /** Art path of a track: the directory's front.jpg (shared by its tracks) else the track's own art. */
    private static String artPath(Snapshot snap, Track t) {
        boolean front = snap.folder(t.parentRef()).map(Folder::hasFront).orElse(false);
        return front ? ArtEndpoint.folderPath(Folder.idOf(t.parentRef()), true) : ArtEndpoint.trackPath(t.id(), true);
    }

    /**
     * @param objectId the DLNA object id of this listing of the track
     * @param context  the container id carried by the stream URL
     */
    private MusicTrack track(Snapshot snap, Track t, String objectId, String parentId, String context, String base,
            DidlFilter filter, ClientProfile profile) {
        Optional<Folder> parent = snap.folder(t.parentRef());
        EffectiveTrack et = EffectiveTrack.of(t, parent.map(Folder::settings).orElse(DirectorySettings.DEFAULT));
        TrackTags tags = t.tags();

        MusicTrack item = new MusicTrack(objectId, parentId, DisplayNames.trackTitle(t), null, null,
                (String) null);
        item.setRestricted(true);
        String artist = firstNonBlank(tags.artist(), et.albumArtist());
        if (artist != null && filter.wants("upnp:artist")) {
            item.setArtists(new PersonWithRole[] { new PersonWithRole(artist) });
        }
        if (artist != null && filter.wants("dc:creator")) {
            item.setCreator(artist);
        }
        if (!tags.allComposers().isEmpty() && filter.wants("upnp:author")) {
            item.addProperty(new DIDLObject.Property.UPNP.AUTHOR(
                    new PersonWithRole(String.join(", ", tags.allComposers()), "Composer")));
        }
        if (et.album() != null && filter.wants("upnp:album")) {
            item.setAlbum(et.album());
        }
        if (et.genre() != null && filter.wants("upnp:genre")) {
            item.addProperty(new DIDLObject.Property.UPNP.GENRE(et.genre()));
        }
        if (tags.trackNo() != null && filter.wants("upnp:originalTrackNumber")) {
            item.setOriginalTrackNumber(tags.trackNo());
        }
        if (et.year() != null && et.year() > 0 && filter.wants("dc:date")) {
            item.setDate(String.format(Locale.ROOT, "%04d-01-01", et.year()));
        }
        if (filter.wants("upnp:albumArtURI") && artwork.trackHasArt(snap, t)) {
            addAlbumArt(item, base + artPath(snap, t), filter);
        }
        if (filter.wants("res")) {
            for (ResourceOffers.Offer offer : offers.offers(t, profile)) {
                item.addResource(resource(t, offer, context, base, filter));
            }
        }
        return item;
    }

    private Res resource(Track t, ResourceOffers.Offer offer, String containerId, String base, DidlFilter filter) {
        Res res = new Res(new ProtocolInfo(offer.protocolInfo()), null, null, null,
                urls.url(base, containerId, t.id(), offer.variant()));
        if (offer.sizeBytes() > 0 && filter.wantsAttribute("res", "size")) {
            res.setSize(offer.sizeBytes());
        }
        if (offer.durationMillis() > 0 && filter.wantsAttribute("res", "duration")) {
            res.setDuration(formatDuration(offer.durationMillis()));
        }
        if (offer.bytesPerSecond() > 0 && filter.wantsAttribute("res", "bitrate")) {
            res.setBitrate(offer.bytesPerSecond());   // DIDL bitrate is bytes per second
        }
        if (offer.sampleRate() > 0 && filter.wantsAttribute("res", "sampleFrequency")) {
            res.setSampleFrequency((long) offer.sampleRate());
        }
        if (offer.channels() > 0 && filter.wantsAttribute("res", "nrAudioChannels")) {
            res.setNrAudioChannels((long) offer.channels());
        }
        return res;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : null;
    }

    /** UPnP duration {@code H+:MM:SS.mmm}. */
    static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", totalSeconds / 3600, (totalSeconds / 60) % 60,
                totalSeconds % 60, millis % 1000);
    }
}
