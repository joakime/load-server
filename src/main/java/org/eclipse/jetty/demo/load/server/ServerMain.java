package org.eclipse.jetty.demo.load.server;

import java.util.EnumSet;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.QoSFilter;
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
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        contextHandler.addServlet(SlowServlet.class, "/slow/*");

        FilterHolder qosHolder = contextHandler.addFilter(QoSFilter.class, "/slow/*", EnumSet.of(DispatcherType.REQUEST));
        qosHolder.setInitParameter("suspendMs", "50");

        server.start();
        server.join();
    }
}
