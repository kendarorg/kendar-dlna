package org.kendar.dlna.it;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jupnp.UpnpServiceImpl;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.controlpoint.SubscriptionCallback;
import org.jupnp.model.action.ActionArgumentValue;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.gena.CancelReason;
import org.jupnp.model.gena.GENASubscription;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteDeviceIdentity;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.meta.Service;
import org.jupnp.model.profile.ClientInfo;
import org.jupnp.model.state.StateVariableValue;
import org.jupnp.model.types.UDAServiceId;
import org.jupnp.model.types.UDN;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.protocol.RetrieveRemoteDescriptors;
import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.DIDLContent;
import org.kendar.dlna.dlna.DlnaConfiguration;
import org.kendar.dlna.platform.JettyHost;

/**
 * A jUPnP control point. It starts the same jUPnP stack as the server (JDK HTTP client, Jetty for the GENA callback
 * server), fetches the device description from a URL given directly (no SSDP) and runs the actions of the
 * ContentDirectory / ConnectionManager services.
 */
final class UpnpClient implements AutoCloseable {

    /** A UPnP error returned by the server. */
    static final class UpnpFailure extends RuntimeException {
        private final int code;

        UpnpFailure(int code, String message) {
            super("UPnP error " + code + ": " + message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }

    /** The output arguments of Browse / Search. */
    record CdsResult(String xml, long returned, long total, long updateId) {
        List<String> ids() {
            return idsInOrder(xml);
        }

        DIDLContent didl() {
            try {
                return xml.isEmpty() ? new DIDLContent() : new DIDLParser().parse(xml);
            } catch (Exception e) {
                throw new AssertionError("Invalid DIDL-Lite: " + e + "\n" + xml, e);
            }
        }
    }

    /** Evented state variable values received through a subscription. */
    static final class Subscription {
        final BlockingQueue<Map<String, String>> events = new LinkedBlockingQueue<>();
        volatile boolean established;
        volatile String failure;
        private SubscriptionCallback callback;

        Map<String, String> next(long seconds) throws InterruptedException {
            return events.poll(seconds, TimeUnit.SECONDS);
        }

        void end() {
            if (callback != null) {
                callback.end();
            }
        }
    }

    private final JettyHost callbackServer;
    private final UpnpServiceImpl upnp;
    private final RemoteDevice device;

    UpnpClient(String descriptionUrl, String uuid) throws Exception {
        callbackServer = new JettyHost(0);
        callbackServer.start();
        upnp = new UpnpServiceImpl(new DlnaConfiguration(callbackServer.contexts(), callbackServer.port()));
        upnp.startup();
        URL url = URI.create(descriptionUrl).toURL();
        RemoteDevice stub = new RemoteDevice(new RemoteDeviceIdentity(UDN.valueOf("uuid:" + uuid), 1800, url, null,
                localAddressFor(url)));
        new RetrieveRemoteDescriptors(upnp, stub).run();
        device = upnp.getRegistry().getRemoteDevice(UDN.valueOf("uuid:" + uuid), true);
        if (device == null) {
            close();
            throw new IllegalStateException("jUPnP could not read the device description at " + descriptionUrl);
        }
    }

    /** The local address used to reach the server (what a real control point learns from the SSDP packet). */
    private static InetAddress localAddressFor(URL url) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(url.getHost()), url.getPort() < 0 ? 80 : url.getPort());
            return s.getLocalAddress();
        }
    }

    RemoteDevice device() {
        return device;
    }

    RemoteService contentDirectory() {
        return device.findService(new UDAServiceId("ContentDirectory"));
    }

    RemoteService connectionManager() {
        return device.findService(new UDAServiceId("ConnectionManager"));
    }

    /** Runs an action; returns its output arguments as strings. @param args alternating name, value */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    Map<String, String> invoke(Service<?, ?> service, String action, String userAgent, Object... args) {
        ActionInvocation invocation = new ActionInvocation(service.getAction(action), new ClientInfo());
        if (userAgent != null) {
            invocation.getClientInfo().setRequestUserAgent(userAgent);
        }
        for (int i = 0; i + 1 < args.length; i += 2) {
            Object v = args[i + 1];
            invocation.setInput((String) args[i],
                    v instanceof Number n ? new UnsignedIntegerFourBytes(n.longValue()) : v);
        }
        new ActionCallback.Default(invocation, upnp.getControlPoint()).run();
        if (invocation.getFailure() != null) {
            throw new UpnpFailure(invocation.getFailure().getErrorCode(), invocation.getFailure().getMessage());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (ActionArgumentValue<?> v : (ActionArgumentValue<?>[]) invocation.getOutput()) {
            out.put(v.getArgument().getName(), String.valueOf(v.getValue()));
        }
        return out;
    }

    CdsResult browse(String id, String flag, String filter, long start, long count, String userAgent) {
        return result(invoke(contentDirectory(), "Browse", userAgent, "ObjectID", id, "BrowseFlag", flag,
                "Filter", filter, "StartingIndex", start, "RequestedCount", count, "SortCriteria", ""));
    }

    CdsResult children(String id, long start, long count) {
        return browse(id, "BrowseDirectChildren", "*", start, count, null);
    }

    CdsResult metadata(String id) {
        return browse(id, "BrowseMetadata", "*", 0, 0, null);
    }

    CdsResult search(String containerId, String criteria, long start, long count) {
        return result(invoke(contentDirectory(), "Search", null, "ContainerID", containerId, "SearchCriteria",
                criteria, "Filter", "*", "StartingIndex", start, "RequestedCount", count, "SortCriteria", ""));
    }

    private static CdsResult result(Map<String, String> out) {
        return new CdsResult(out.get("Result"), Long.parseLong(out.get("NumberReturned")),
                Long.parseLong(out.get("TotalMatches")), Long.parseLong(out.get("UpdateID")));
    }

    /** Object ids of the DIDL-Lite result in document order (jUPnP's model separates containers and items). */
    static List<String> idsInOrder(String xml) {
        List<String> ids = new ArrayList<>();
        if (xml == null || xml.isEmpty()) {
            return ids;
        }
        try {
            var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setNamespaceAware(true);
            var doc = f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            var kids = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof org.w3c.dom.Element e && e.hasAttribute("id")) {
                    ids.add(e.getAttribute("id"));
                }
            }
        } catch (Exception e) {
            throw new AssertionError("Not well-formed DIDL-Lite: " + e, e);
        }
        return ids;
    }

    long systemUpdateId() {
        return Long.parseLong(invoke(contentDirectory(), "GetSystemUpdateID", null).get("Id"));
    }

    /** Subscribes to the ContentDirectory events (GENA). The callback URL is built from this machine's address. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    Subscription subscribe(int seconds) {
        Subscription s = new Subscription();
        SubscriptionCallback cb = new SubscriptionCallback(contentDirectory(), seconds) {
            @Override
            protected void failed(GENASubscription subscription, UpnpResponse response, Exception exception,
                    String defaultMsg) {
                s.failure = defaultMsg;
            }

            @Override
            protected void established(GENASubscription subscription) {
                s.established = true;
            }

            @Override
            protected void ended(GENASubscription subscription, CancelReason reason, UpnpResponse response) {
                // nothing to do
            }

            @Override
            protected void eventReceived(GENASubscription subscription) {
                Map<String, String> values = new LinkedHashMap<>();
                Map<String, StateVariableValue> current = subscription.getCurrentValues();
                for (Map.Entry<String, StateVariableValue> e : current.entrySet()) {
                    values.put(e.getKey(), String.valueOf(e.getValue().getValue()));
                }
                s.events.add(values);
            }

            @Override
            protected void eventsMissed(GENASubscription subscription, int numberOfMissedEvents) {
                // nothing to do
            }
        };
        s.callback = cb;
        upnp.getControlPoint().execute(cb);
        return s;
    }

    @Override
    public void close() {
        try {
            upnp.shutdown();
        } finally {
            try {
                callbackServer.stop();
            } catch (Exception e) {
                // shutting down
            }
        }
    }
}
