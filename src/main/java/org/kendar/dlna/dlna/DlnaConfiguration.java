package org.kendar.dlna.dlna;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.jupnp.DefaultUpnpServiceConfiguration;
import org.jupnp.model.Namespace;
import org.jupnp.transport.impl.ServletStreamServerConfigurationImpl;
import org.jupnp.transport.impl.ServletStreamServerImpl;
import org.jupnp.transport.impl.jetty.StreamClientConfigurationImpl;
import org.jupnp.transport.spi.NetworkAddressFactory;
import org.jupnp.transport.spi.StreamClient;
import org.jupnp.transport.spi.StreamServer;

/** jUPnP configuration: UPnP HTTP on the shared Jetty (under {@value #BASE_PATH}), JDK HTTP client, virtual threads. */
public final class DlnaConfiguration extends DefaultUpnpServiceConfiguration {

    public static final String BASE_PATH = "/upnp";

    private final ContextHandlerCollection contexts;
    private final int port;
    private final SharedJettyServletContainer container;
    private final ExecutorService clientExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DlnaConfiguration(ContextHandlerCollection contexts, int port) {
        super(port);
        this.contexts = contexts;
        this.port = port;
        this.container = new SharedJettyServletContainer(contexts, port);
    }

    @Override
    protected Namespace createNamespace() {
        return new Namespace(BASE_PATH);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public StreamServer createStreamServer(NetworkAddressFactory networkAddressFactory) {
        return new ServletStreamServerImpl(
                new ServletStreamServerConfigurationImpl(container, port));
    }

    @Override
    @SuppressWarnings("rawtypes")
    public StreamClient createStreamClient() {
        return new JdkStreamClient(new StreamClientConfigurationImpl(clientExecutor));
    }

    @Override
    protected ExecutorService createDefaultExecutorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void shutdown() {
        super.shutdown();
        clientExecutor.shutdownNow();
    }
}
