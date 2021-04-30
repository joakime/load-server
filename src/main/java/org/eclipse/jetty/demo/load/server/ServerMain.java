package org.eclipse.jetty.demo.load.server;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class ServerMain
{
    public static void main(String[] args) throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("server");
        Server server = new Server(threadPool);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(9999);
        connector.setIdleTimeout(TimeUnit.SECONDS.toMillis(90));
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        contextHandler.addServlet(SlowBlockingServlet.class, "/slow/blocking/*");
        contextHandler.addServlet(SlowAsyncServlet.class, "/slow/async/*");

        FilterHolder qosHolder = contextHandler.addFilter(CustomQoSFilter.class, "/slow/*", EnumSet.of(DispatcherType.REQUEST));
        qosHolder.setInitParameter("maxRequests", "5");
        qosHolder.setInitParameter("suspendMs", "230");

        server.setHandler(contextHandler);

        server.start();
        server.join();
    }
}
