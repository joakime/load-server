package org.eclipse.jetty.demo.load.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class CustomQoSFilter implements Filter
{
    private static final Logger LOG = Log.getLogger(CustomQoSFilter.class);
    static final int __DEFAULT_MAX_PRIORITY = 10;
    static final int __DEFAULT_PASSES = 0;
    static final int __DEFAULT_WAIT_MS = 50;
    static final long __DEFAULT_TIMEOUT_MS = 50;
    static final String MANAGED_ATTR_INIT_PARAM = "managedAttr";
    static final String MAX_REQUESTS_INIT_PARAM = "maxRequests";
    static final String MAX_PRIORITY_INIT_PARAM = "maxPriority";
    static final String MAX_WAIT_INIT_PARAM = "waitMs";
    static final String SUSPEND_INIT_PARAM = "suspendMs";
    private final String _suspended = "CustomQoSFilter@" + Integer.toHexString(hashCode()) + ".SUSPENDED";
    private final String _resumed = "CustomQoSFilter@" + Integer.toHexString(hashCode()) + ".RESUMED";
    private long _waitMs;
    private long _suspendMs;
    private int _maxRequests;
    private Semaphore _passes;
    private Queue<AsyncContext>[] _queues;
    private AsyncListener[] _listeners;
    private static HashMap<String, CustomQoSFilter> _filters;

    public void init(FilterConfig filterConfig)
    {
        int max_priority = __DEFAULT_MAX_PRIORITY;
        String name = filterConfig.getFilterName();
        if (_filters == null)
        {
            _filters = new HashMap<>();
        }
        _filters.put(name, this);

        if (filterConfig.getInitParameter(MAX_PRIORITY_INIT_PARAM) != null)
            max_priority = Integer.parseInt(filterConfig.getInitParameter(MAX_PRIORITY_INIT_PARAM));
        _queues = new Queue[max_priority + 1];
        _listeners = new AsyncListener[_queues.length];
        for (int p = 0; p < _queues.length; ++p)
        {
            _queues[p] = new ConcurrentLinkedQueue<>();
            _listeners[p] = new QoSAsyncListener(p);
        }
        int maxRequests = __DEFAULT_PASSES;
        if (filterConfig.getInitParameter(MAX_REQUESTS_INIT_PARAM) != null)
            maxRequests = Integer.parseInt(filterConfig.getInitParameter(MAX_REQUESTS_INIT_PARAM));
        if (maxRequests > 0)
            _passes = new Semaphore(maxRequests, true);
        _maxRequests = maxRequests;
        long wait = __DEFAULT_WAIT_MS;
        if (filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM) != null)
            wait = Integer.parseInt(filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM));
        _waitMs = wait;
        long suspend = __DEFAULT_TIMEOUT_MS;
        if (filterConfig.getInitParameter(SUSPEND_INIT_PARAM) != null)
            suspend = Integer.parseInt(filterConfig.getInitParameter(SUSPEND_INIT_PARAM));
        _suspendMs = suspend;
        ServletContext context = filterConfig.getServletContext();
        if (context != null && Boolean.parseBoolean(filterConfig.getInitParameter(MANAGED_ATTR_INIT_PARAM)))
            context.setAttribute(filterConfig.getFilterName(), this);
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException
    {
        boolean accepted = false;
        try
        {
            if (_maxRequests == 0)
            {
                // Just accept everything
                chain.doFilter(request, response);
            }
            else
            {
                // We have some sort of throttling.
                Boolean suspended = (Boolean)request.getAttribute(_suspended);
                if (suspended == null)
                {
                    accepted = _passes.tryAcquire(getWaitMs(), TimeUnit.MILLISECONDS);
                    if (accepted)
                    {
                        request.setAttribute(_suspended, Boolean.FALSE);
                        LOG.debug("Accepted - (availablePermits={}) {}", _passes.availablePermits(), request);
                    }
                    else
                    {
                        request.setAttribute(_suspended, Boolean.TRUE);
                        int priority = getPriority(request);
                        AsyncContext asyncContext = request.startAsync();
                        long suspendMs = getSuspendMs();
                        if (suspendMs > 0)
                            asyncContext.setTimeout(suspendMs);
                        asyncContext.addListener(_listeners[priority]);
                        _queues[priority].add(asyncContext);
                        LOG.debug("Suspend - (availablePermits={}) {}", _passes.availablePermits(), request);
                        return;
                    }
                }
                else
                {
                    if (suspended)
                    {
                        request.setAttribute(_suspended, Boolean.FALSE);
                        Boolean resumed = (Boolean)request.getAttribute(_resumed);
                        if (resumed == Boolean.TRUE)
                        {
                            _passes.acquire();
                            accepted = true;
                            LOG.debug("Resumed - (availablePermits={}) {}", _passes.availablePermits(), request);
                        }
                        else
                        {
                            // Timeout! try 1 more time.
                            accepted = _passes.tryAcquire(getWaitMs(), TimeUnit.MILLISECONDS);
                            LOG.debug("Timeout - (availablePermits={}) {}", _passes.availablePermits(), request);
                        }
                    }
                    else
                    {
                        // Pass through resume of previously accepted request.
                        _passes.acquire();
                        accepted = true;
                        LOG.debug("Passthrough - (availablePermits={}) {}", _passes.availablePermits(), request);
                    }
                }
                if (accepted)
                {
                    chain.doFilter(request, response);
                }
                else
                {
                    LOG.debug("Rejected - (availablePermits={}) {}", _passes.availablePermits(), request);
                    ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                }
            }
        }
        catch (InterruptedException e)
        {
            ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        finally
        {
            if (_maxRequests != 0)
            {
                if (accepted)
                {
                    _passes.release();

                    for (int p = _queues.length - 1; p >= 0; --p)
                    {
                        AsyncContext asyncContext = _queues[p].poll();
                        if (asyncContext != null)
                        {
                            ServletRequest candidate = asyncContext.getRequest();
                            Boolean suspended = (Boolean)candidate.getAttribute(_suspended);
                            if (suspended == Boolean.TRUE)
                            {
                                try
                                {
                                    candidate.setAttribute(_resumed, Boolean.TRUE);
                                    asyncContext.dispatch();
                                    break;
                                }
                                catch (Exception x)
                                {
                                    LOG.warn("QOS Exception", x);
                                    continue;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes the request priority.
     * <p>
     * The default implementation assigns the following priorities:
     * <ul>
     * <li>2 - for an authenticated request
     * <li>1 - for a request with valid / non new session
     * <li>0 - for all other requests.
     * </ul>
     * This method may be overridden to provide application specific priorities.
     *
     * @param request the incoming request
     * @return the computed request priority
     */
    protected int getPriority(ServletRequest request)
    {
        HttpServletRequest baseRequest = (HttpServletRequest)request;
        if (baseRequest.getUserPrincipal() != null)
        {
            return 2;
        }
        else
        {
            HttpSession session = baseRequest.getSession(false);
            if (session != null && !session.isNew())
                return 1;
            else
                return 0;
        }
    }

    public void destroy()
    {
    }

    /**
     * Get the (short) amount of time (in milliseconds) that the filter would wait for the semaphore to become available
     * before suspending a request.
     *
     * @return wait time (in milliseconds)
     */
    public long getWaitMs()
    {
        return _waitMs;
    }

    /**
     * Set the (short) amount of time (in milliseconds) that the filter would wait for the semaphore to become available
     * before suspending a request.
     *
     * @param value wait time (in milliseconds)
     */
    public void setWaitMs(long value)
    {
        _waitMs = value;
    }

    /**
     * Get the amount of time (in milliseconds) that the filter would suspend a request for while waiting for the
     * semaphore to become available.
     *
     * @return suspend time (in milliseconds)
     */
    public long getSuspendMs()
    {
        return _suspendMs;
    }

    /**
     * Set the amount of time (in milliseconds) that the filter would suspend a request for while waiting for the
     * semaphore to become available.
     *
     * @param value suspend time (in milliseconds)
     */
    public void setSuspendMs(long value)
    {
        if (value > 0)
        {
            _suspendMs = value;
        }
    }

    /**
     * Get the maximum number of requests allowed to be processed at the same time.
     *
     * @return maximum number of requests
     */
    public int getMaxRequests()
    {
        return _maxRequests;
    }

    /**
     * Set the maximum number of requests allowed to be processed at the same time.
     *
     * @param value the number of requests
     */
    public void setMaxRequests(int value)
    {
        _maxRequests = value;
        if (value != 0)
        {
            _passes = new Semaphore(_maxRequests, Boolean.TRUE);
            LOG.info("maxRequests set to {} available {}", _maxRequests, _passes.availablePermits());
        }
        else
        {
            LOG.info("maxRequests 0 no QoS is running");
        }
    }

    private class QoSAsyncListener implements AsyncListener
    {
        private final int priority;

        public QoSAsyncListener(int priority)
        {
            this.priority = priority;
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            try
            {
                LOG.info("onTimeout({})", event);
                // Remove before it's redispatched, so it won't be
                // redispatched again at the end of the filtering.
                AsyncContext asyncContext = event.getAsyncContext();
                _queues[priority].remove(asyncContext);

                ((HttpServletResponse)event.getSuppliedResponse()).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                asyncContext.complete();
            }
            catch (Throwable t)
            {
                LOG.warn("onTimeout() ERROR", t);
                throw t;
            }
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            LOG.warn("onError({})", event.getThrowable());
        }
    }
}