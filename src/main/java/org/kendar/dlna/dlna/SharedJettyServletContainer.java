package org.kendar.dlna.dlna;

import java.util.concurrent.ExecutorService;

import javax.servlet.Servlet;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.jupnp.transport.spi.ServletContainerAdapter;

/**
 * Lets jUPnP serve its UPnP requests from the application's shared Jetty server instead of starting its own.
 * The Jetty lifecycle (and port) is owned by the platform; this adapter only registers a servlet context.
 */
public final class SharedJettyServletContainer implements ServletContainerAdapter {

    private final ContextHandlerCollection contexts;
    private final int port;
    private ServletContextHandler context;

    /** @param port the actual, already bound port of the shared Jetty connector */
    public SharedJettyServletContainer(ContextHandlerCollection contexts, int port) {
        this.contexts = contexts;
        this.port = port;
    }

    @Override
    public void setExecutorService(ExecutorService executorService) {
        // Jetty's own (virtual thread) executor is used.
    }

    @Override
    public int addConnector(String host, int requestedPort) {
        return port; // single shared port
    }

    @Override
    public synchronized void registerServlet(String contextPath, Servlet servlet) {
        if (context != null) {
            return;
        }
        ServletContextHandler handler = new ServletContextHandler();
        handler.setContextPath(contextPath.isEmpty() ? "/" : contextPath);
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        handler.addServlet(holder, "/*");
        contexts.addHandler(handler.getCoreContextHandler());
        try {
            if (contexts.isStarted() && !handler.isStarted()) {
                handler.start();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start UPnP servlet context", e);
        }
        context = handler;
    }

    @Override
    public void startIfNotRunning() {
        // lifecycle owned by the platform
    }

    @Override
    public synchronized void stopIfRunning() {
        if (context != null) {
            contexts.removeHandler(context.getCoreContextHandler());
            try {
                context.stop();
            } catch (Exception e) {
                // shutting down anyway
            }
            context = null;
        }
    }
}
