package org.kendar.dlna.platform;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

import org.kendar.dlna.adminweb.AdminApi;
import org.kendar.dlna.adminweb.AdminServices;
import org.kendar.dlna.adminweb.ApiHandler;
import org.kendar.dlna.adminweb.BuildInfo;
import org.kendar.dlna.adminweb.ResetPasswordWatcher;
import org.kendar.dlna.adminweb.StaticHandler;
import org.kendar.dlna.adminweb.WebStream;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.curation.FolderService;
import org.kendar.dlna.curation.PerFormatTagWriter;
import org.kendar.dlna.curation.PlaylistService;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.catalog.CatalogNavigation;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.clients.ClientProfileResolver;
import org.kendar.dlna.clients.ClientProfiles;
import org.kendar.dlna.delivery.CompositeNavigation;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.delivery.Prefetcher;
import org.kendar.dlna.delivery.ResourceOffers;
import org.kendar.dlna.delivery.StreamHandler;
import org.kendar.dlna.delivery.StreamResolver;
import org.kendar.dlna.delivery.TranscodeCache;
import org.kendar.dlna.delivery.TranscodeCacheInvalidator;
import org.kendar.dlna.delivery.TranscodeService;
import org.kendar.dlna.dlna.ArtEndpoint;
import org.kendar.dlna.dlna.ArtHandler;
import org.kendar.dlna.dlna.BrowseEngine;
import org.kendar.dlna.dlna.DeviceUuidStore;
import org.kendar.dlna.dlna.DlnaServer;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.libraryquery.LibraryQuery;
import org.kendar.dlna.identity.PasswordHasher;
import org.kendar.dlna.identity.SessionStore;
import org.kendar.dlna.ingestion.CatalogService;
import org.kendar.dlna.ingestion.CatalogStartup;
import org.kendar.dlna.ingestion.CronExpression;
import org.kendar.dlna.ingestion.EmbeddedArtExtractor;
import org.kendar.dlna.ingestion.JsonCatalogStore;
import org.kendar.dlna.ingestion.RescanService;
import org.kendar.dlna.ingestion.RootsFingerprint;
import org.kendar.dlna.ingestion.ScanScheduler;
import org.kendar.dlna.ingestion.LibraryScanner;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.views.Views;

/** Composition root. */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    /** Strong reference: java.util.logging only holds loggers weakly, which would drop the level set below. */
    private static final Logger JUPNP_TRANSPORT_LOG = Logger.getLogger("org.jupnp.transport");

    private Main() {
    }

    /**
     * jUPnP's transport layer logs one INFO line per HTTP request ("HttpServlet.service(): id: 0, request URI ...").
     * Only warnings and errors are kept, unless the user supplies a {@code java.util.logging.config.file}.
     */
    static void quietJupnpTransportLogs() {
        if (System.getProperty("java.util.logging.config.file") == null) {
            JUPNP_TRANSPORT_LOG.setLevel(java.util.logging.Level.WARNING);
        }
    }

    public static void main(String[] args) throws Exception {
        quietJupnpTransportLogs();
        System.setProperty("java.awt.headless", "true");
        UnaryOperator<String> settings = ConfigLoader.lookup(System.getProperties(), System.getenv());
        Path configDir = ConfigLoader.configDir(settings);
        AppConfig config;
        try {
            config = ConfigLoader.load(configDir, settings);
        } catch (ConfigLoader.ConfigException e) {
            LOG.severe("Invalid configuration in " + configDir + ": " + e.getMessage());
            System.exit(2);
            return;
        }

        LibraryRoots roots = new LibraryRoots(config.library().roots());
        if (roots.roots().isEmpty()) {
            LOG.warning("No library roots configured (library.roots in config.json): the library will be empty");
        }
        Clock clock = Clock.systemDefaultZone();   // cron expressions fire in the local time zone
        Path dataDir = Path.of(config.library().dataDir());
        Path playlistsDir = Path.of(config.library().playlistsDir());
        String version = Main.class.getPackage().getImplementationVersion();
        AppConfig.Transcode tc = config.transcode();
        TranscodingWiring transcoding = TranscodingWiring.create(tc, dataDir);
        LOG.info("Transcoding " + (tc.enabled() ? "on, MP3 " + tc.bitrateKbps() + " kbps, prefetch "
                + (tc.prefetch() ? "on" : "off") : "off") + ", MIDI "
                + (transcoding.midiAvailable ? "available" : "unavailable (" + transcoding.midiReason() + ")"));
        if (transcoding.ffmpegVersion != null) {
            LOG.info("ffmpeg (WMA, APE): " + transcoding.ffmpegVersion + " [" + transcoding.ffmpegBinary + "]");
        } else {
            LOG.warning("ffmpeg NOT FOUND (" + transcoding.ffmpegBinary + "): WMA and APE files will be listed as unsupported. "
                    + "Install ffmpeg or set transcode.ffmpegPath in config.json / KENDAR_FFMPEG_PATH");
        }
        LibraryScanner scanner = new LibraryScanner(transcoding.tagReader(), Clock.systemUTC(), tc.enabled());
        JsonCatalogStore store = new JsonCatalogStore(dataDir,
                RootsFingerprint.of(roots.roots()) + transcoding.fingerprintSuffix() + (tc.enabled() ? "|sections" : ""),
                version == null ? "dev" : version, false);
        long t0 = System.nanoTime();
        Duration catchUp = Duration.ofHours(config.scan().catchUpThresholdHours());
        CatalogStartup.Result started = CatalogStartup.start(store, scanner, roots.roots(), playlistsDir, clock,
                catchUp);
        LOG.info("Catalog ready in " + (System.nanoTime() - t0) / 1_000_000 + " ms ("
                + (started.loadedFromIndex() ? "persisted index" : "full scan") + ")");
        CatalogService catalog = started.catalog();
        SnapshotHolder holder = catalog.holder();
        RescanService rescans = new RescanService(scanner, catalog, roots.roots(), playlistsDir, clock);

        JettyHost jetty = new JettyHost(config.network().port(),
                new NetworkPolicy(config.network().lanCidrs(), config.network().trustedProxies()));
        Shuffle shuffle = new Shuffle(holder::current, Clock.systemUTC(),
                Duration.ofMinutes(config.random().ttlMinutes()));
        Views views = new Views(holder::current, config.views().bucketThreshold());
        TranscodeCache cache = new TranscodeCache(dataDir.resolve("transcode"), tc.cacheMaxMb() * 1024L * 1024L,
                Duration.ofHours(tc.cacheTtlHours()), Clock.systemUTC());
        TranscodeService transcode = new TranscodeService(transcoding.transcoder, cache, tc.enabled(),
                tc.bitrateKbps(), transcoding.midiStamp,
                (track, samples, millis) -> catalog.recordRenderedLength(track, samples, millis));
        catalog.addListener(new TranscodeCacheInvalidator(cache, holder::current));
        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "transcode-cache-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(cache::sweep, 10, 60, TimeUnit.MINUTES);
        ScheduledExecutorService adminTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "admin-timer");
            t.setDaemon(true);
            return t;
        });
        CompositeNavigation navigation = new CompositeNavigation(new CatalogNavigation(holder::current), shuffle,
                views);
        Prefetcher prefetcher = tc.enabled() && tc.prefetch()
                ? new Prefetcher(transcode, navigation, roots, Prefetcher.lowPriorityPool(tc.prefetchJobs()))
                : null;
        StreamResolver streams = new StreamResolver(holder::current, roots,
                ref -> {
                    LOG.warning("File missing on disk, requested by a client: " + ref);
                    catalog.removeTrack(ref);
                },
                navigation, transcode, prefetcher);
        ClientProfileResolver clients = ClientProfiles.resolver(config.clients(), configDir);
        ActiveStreams activeStreams = new ActiveStreams();
        jetty.contexts().addHandler(new StreamHandler(streams, activeStreams).asContext());
        EmbeddedArtExtractor extractor = new EmbeddedArtExtractor();
        ArtworkService artwork = new ArtworkService(holder, roots, ref -> {
            Path file = roots.resolve(ref);
            return file == null ? java.util.Optional.empty() : extractor.extract(file);
        }, dataDir.resolve("thumbs"));
        jetty.contexts().addHandler(new ArtHandler(new ArtEndpoint(artwork)).asContext());
        SessionStore sessions = new SessionStore(Clock.systemUTC());
        IdentityService identity = new IdentityService(dataDir.resolve("users"),
                List.copyOf(roots.roots().keySet()), config.library().effectiveMainRoot(), new PasswordHasher(),
                sessions, Clock.systemUTC(), new SecureRandom(), IdentityService.logAnnouncer());
        identity.applyAdminOverride(settings.apply("adminUser"), settings.apply("adminPassword"));
        identity.bootstrap(new ConfigAccountStore(configDir).load());
        identity.syncRoots(roots.roots().keySet());
        ResetPasswordWatcher resetWatcher = new ResetPasswordWatcher(configDir, identity);
        resetWatcher.check();
        adminTimer.scheduleWithFixedDelay(resetWatcher::check, ResetPasswordWatcher.INTERVAL_SECONDS,
                ResetPasswordWatcher.INTERVAL_SECONDS, TimeUnit.SECONDS);
        TagService tagService = new TagService(catalog, roots, new PerFormatTagWriter(),
                transcoding.plainTagReader());
        AdminServices adminServices = new AdminServices(catalog, rescans, tagService,
                new FolderService(catalog, roots, tagService), new PlaylistService(catalog, playlistsDir), shuffle,
                activeStreams, cache, () -> prefetcher == null ? 0 : prefetcher.activeJobs(), BuildInfo.load(), clock,
                configDir, ConfigLoader::validateJson);
        AdminApi adminApi = new AdminApi(adminServices, identity,
                new LibraryQuery(holder::current, views, artwork),
                new WebStream(holder::current, roots, streams, transcode, prefetcher, activeStreams));
        jetty.contexts().addHandler(new ApiHandler(adminApi).asContext());
        adminTimer.scheduleWithFixedDelay(adminApi.personal()::maintain, 30, 30, TimeUnit.SECONDS);
        jetty.contexts().addHandler(new StaticHandler().asContext());
        jetty.contexts().addHandler(new StaticHandler(StaticHandler.APP_PREFIX).asContext());
        jetty.start();
        int port = jetty.port();

        BrowseEngine browse = new BrowseEngine(holder, new DefaultStreamUrlBuilder(),
                () -> LocalAddress.baseUrl(config.network().advertiseAddress(), port), artwork, shuffle, views,
                new ResourceOffers(transcode), clients);
        UUID uuid = DeviceUuidStore.loadOrCreate(dataDir);
        DlnaServer dlna = new DlnaServer(uuid, config.server().friendlyName(), jetty.contexts(), port, browse);
        dlna.start();
        catalog.addListener(dlna.changeListener());
        ScanScheduler scheduler = new ScanScheduler(rescans, CronExpression.parse(config.scan().cron()), clock);
        scheduler.start();
        scheduler.catchUpIfStale(catalog.current().scannedAt(), catchUp);
        LOG.info("Kendar DLNA '" + config.server().friendlyName() + "' uuid=" + uuid + " listening on port " + port);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                scheduler.close();
                sweeper.shutdownNow();
                adminTimer.shutdownNow();
                adminApi.personal().flushAll();
                if (prefetcher != null) {
                    prefetcher.close();
                }
                rescans.close();
                dlna.stop();   // sends ssdp:byebye
                jetty.stop();
            } catch (Exception e) {
                LOG.warning("Shutdown error: " + e);
            } finally {
                stopped.countDown();
            }
        }, "shutdown"));
        stopped.await();
    }
}
