package org.kendar.dlna.dlna;

import java.util.UUID;

import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder;
import org.jupnp.model.DefaultServiceManager;
import org.jupnp.model.meta.DeviceDetails;
import org.jupnp.model.meta.DeviceIdentity;
import org.jupnp.model.meta.LocalDevice;
import org.jupnp.model.meta.LocalService;
import org.jupnp.model.meta.ManufacturerDetails;
import org.jupnp.model.meta.ModelDetails;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDAServiceId;
import org.jupnp.model.types.UDN;
import org.jupnp.support.connectionmanager.ConnectionManagerService;
import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.ProtocolInfos;
import org.kendar.dlna.catalog.CatalogChangeListener;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.delivery.MediaTypes;
import org.kendar.dlna.delivery.StreamVariant;

/** Starts jUPnP (SSDP + descriptions) and registers the MediaServer:1 device. Stop sends ssdp:byebye. */
public final class DlnaServer {

    private final UUID uuid;
    private final String friendlyName;
    private final UpnpServiceImpl upnp;
    private final BrowseEngine engine;
    private volatile LibraryContentDirectory contentDirectory;

    /** @param contexts the shared Jetty's context collection; @param port its actual bound port */
    public DlnaServer(UUID uuid, String friendlyName, ContextHandlerCollection contexts, int port,
            BrowseEngine engine) {
        this.engine = engine;
        this.uuid = uuid;
        this.friendlyName = friendlyName;
        this.upnp = new UpnpServiceImpl(new DlnaConfiguration(contexts, port)) {
            @Override
            protected org.jupnp.protocol.ProtocolFactory createProtocolFactory() {
                return new ServerOnlyProtocolFactory(this);
            }
        };
    }

    public void start() throws Exception {
        // jUPnP warns on every action argument it deliberately shapes for WMP12 compatibility: pure noise here
        org.jupnp.util.SpecificationViolationReporter.disableReporting();
        upnp.startup();
        LocalDevice device = createDevice(uuid, friendlyName, engine);
        upnp.getRegistry().addDevice(device);
        @SuppressWarnings("unchecked")
        LocalService<LibraryContentDirectory> cd = device.findService(new UDAServiceId("ContentDirectory"));
        contentDirectory = cd.getManager().getImplementation();
    }

    /**
     * Listener for committed catalog changes: announces the evented SystemUpdateID / ContainerUpdateIDs to
     * subscribed control points (a no-op until the device is started).
     */
    public CatalogChangeListener changeListener() {
        return UpdateEvents.announcer(engine::snapshot, () -> contentDirectory);
    }

    public void stop() {
        upnp.shutdown();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static LocalDevice createDevice(UUID uuid, String friendlyName, BrowseEngine engine) throws Exception {
        AnnotationLocalServiceBinder binder = new ClientAwareServiceBinder();

        LocalService<LibraryContentDirectory> cd = binder.read(LibraryContentDirectory.class);
        cd.setManager(new DefaultServiceManager<LibraryContentDirectory>(cd, null) {
            @Override
            protected LibraryContentDirectory createServiceInstance() {
                return new LibraryContentDirectory(engine);
            }
        });

        LocalService<ConnectionManagerService> cm = binder.read(ConnectionManagerService.class);
        cm.setManager(new DefaultServiceManager<ConnectionManagerService>(cm, null) {
            @Override
            protected ConnectionManagerService createServiceInstance() {
                return new ConnectionManagerService(sourceProtocols(), new ProtocolInfos());
            }
        });

        return new LocalDevice(
                new DeviceIdentity(new UDN(uuid)),
                new UDADeviceType("MediaServer", 1),
                new DeviceDetails(friendlyName,
                        new ManufacturerDetails("Kendar"),
                        new ModelDetails("KendarDLNA", "Java DLNA music server", "1")),
                new LocalService[] { cd, cm });
    }

    static ProtocolInfos sourceProtocols() {
        ProtocolInfos infos = new ProtocolInfos();
        for (Format f : Format.values()) {
            if (!f.rendered()) {   // MIDI and ABC are only offered rendered
                infos.add(new ProtocolInfo(MediaTypes.protocolInfo(f)));
            }
        }
        infos.add(new ProtocolInfo(MediaTypes.transcodedMp3ProtocolInfo()));
        for (int rate : new int[] { 32000, 44100, 48000 }) {
            infos.add(new ProtocolInfo(MediaTypes.lpcmProtocolInfo(rate)));
        }
        for (int rate : new int[] { 44100, 48000, 88200, 96000, 176400, 192000 }) {
            infos.add(new ProtocolInfo(MediaTypes.protocolInfo(StreamVariant.pcm(StreamVariant.Kind.LPCM, 24, 0), rate)));
        }
        for (StreamVariant.Kind k : new StreamVariant.Kind[] { StreamVariant.Kind.WAV, StreamVariant.Kind.FLAC,
                StreamVariant.Kind.ALAC }) {
            infos.add(new ProtocolInfo(MediaTypes.protocolInfo(StreamVariant.pcm(k, 16, 0), 0)));
        }
        return infos;
    }
}
