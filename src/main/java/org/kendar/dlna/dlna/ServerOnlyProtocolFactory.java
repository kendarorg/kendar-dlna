package org.kendar.dlna.dlna;

import org.jupnp.UpnpService;
import org.jupnp.model.message.IncomingDatagramMessage;
import org.jupnp.model.message.UpnpRequest;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.protocol.ProtocolFactoryImpl;
import org.jupnp.protocol.ReceivingAsync;

/**
 * Protocol factory of a pure media <em>server</em>: SSDP announcements and search responses of other devices are
 * ignored, so jUPnP never fetches their descriptors, never keeps a registry of remote devices and never logs their
 * specification violations. Our own announcements, M-SEARCH answers and the control/eventing endpoints are untouched.
 */
final class ServerOnlyProtocolFactory extends ProtocolFactoryImpl {

    ServerOnlyProtocolFactory(UpnpService upnpService) {
        super(upnpService);
    }

    /** ssdp:alive / ssdp:byebye of other devices. */
    @Override
    protected ReceivingAsync createReceivingNotification(IncomingDatagramMessage<UpnpRequest> incomingRequest) {
        return null;
    }

    /** Answers to a search we sent (we send none, but other control points' searches can be answered by others). */
    @Override
    protected ReceivingAsync createReceivingSearchResponse(IncomingDatagramMessage<UpnpResponse> incomingResponse) {
        return null;
    }
}
