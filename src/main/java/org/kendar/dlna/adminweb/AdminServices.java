package org.kendar.dlna.adminweb;

import java.time.Clock;
import java.util.function.IntSupplier;

import org.kendar.dlna.curation.FolderService;
import org.kendar.dlna.curation.PlaylistService;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.delivery.TranscodeCache;
import org.kendar.dlna.ingestion.CatalogService;
import org.kendar.dlna.ingestion.RescanService;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.catalog.NavigationRegistry;

/**
 * What the REST resources work with. {@code cache} is null when transcoding is off; {@code prefetchJobs}
 * reports the running prefetch jobs. {@code navigations} is the live set of configured navigations (null = the
 * navigation editor is not offered).
 */
public record AdminServices(CatalogService catalog, RescanService rescans, TagService tags, FolderService folders,
        PlaylistService playlists, Shuffle shuffle, ActiveStreams streams, TranscodeCache cache,
        IntSupplier prefetchJobs, BuildInfo build, Clock clock, java.nio.file.Path configDir,
        java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> configValidator,
        NavigationRegistry navigations, org.kendar.dlna.lyrics.LyricsService lyrics) {

    /** Without lyrics. */
    public AdminServices(CatalogService catalog, RescanService rescans, TagService tags, FolderService folders,
            PlaylistService playlists, Shuffle shuffle, ActiveStreams streams, TranscodeCache cache,
            IntSupplier prefetchJobs, BuildInfo build, Clock clock, java.nio.file.Path configDir,
            java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> configValidator,
            NavigationRegistry navigations) {
        this(catalog, rescans, tags, folders, playlists, shuffle, streams, cache, prefetchJobs, build, clock,
                configDir, configValidator, navigations, null);
    }

    /** Without a navigation registry. */
    public AdminServices(CatalogService catalog, RescanService rescans, TagService tags, FolderService folders,
            PlaylistService playlists, Shuffle shuffle, ActiveStreams streams, TranscodeCache cache,
            IntSupplier prefetchJobs, BuildInfo build, Clock clock, java.nio.file.Path configDir,
            java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> configValidator) {
        this(catalog, rescans, tags, folders, playlists, shuffle, streams, cache, prefetchJobs, build, clock,
                configDir, configValidator, null, null);
    }

    /** Without the config directory (the server settings export/import is then not offered). */
    public AdminServices(CatalogService catalog, RescanService rescans, TagService tags, FolderService folders,
            PlaylistService playlists, Shuffle shuffle, ActiveStreams streams, TranscodeCache cache,
            IntSupplier prefetchJobs, BuildInfo build, Clock clock) {
        this(catalog, rescans, tags, folders, playlists, shuffle, streams, cache, prefetchJobs, build, clock, null, null,
                null, null);
    }
}
