package org.eclipse.jetty.demo.load.server;

import java.io.IOException;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SlowAsyncServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        if (req.getDispatcherType() == DispatcherType.REQUEST)
        {
            AsyncContext context = req.startAsync(req, resp);

            long duration = Long.parseLong(req.getParameter("duration"));
            context.setTimeout(duration);
            context.addListener(new AsyncListener()
            {
                @Override
                public void onComplete(AsyncEvent event) throws IOException
                {

                }

                @Override
                public void onTimeout(AsyncEvent event) throws IOException
                {
                    AsyncContext asyncContext = event.getAsyncContext();
                    ((HttpServletResponse)event.getSuppliedResponse()).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    asyncContext.complete();
                }

                @Override
                public void onError(AsyncEvent event) throws IOException
                {
                }

                @Override
                public void onStartAsync(AsyncEvent event) throws IOException
                {
                }
            });
        }
    }
}
