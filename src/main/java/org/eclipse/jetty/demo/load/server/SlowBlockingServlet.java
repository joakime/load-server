package org.eclipse.jetty.demo.load.server;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SlowBlockingServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        resp.setCharacterEncoding("utf-8");
        resp.setContentType("text/plain");
        long duration = Long.parseLong(req.getParameter("duration"));
        try
        {
            TimeUnit.MILLISECONDS.sleep(duration);
        }
        catch (InterruptedException e)
        {
        }
        resp.getWriter().println("This is the " + this.getClass().getName());
    }
}
