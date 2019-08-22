/**
 * Add a class comment here
 */

/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */



import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.collect.ImmutableList;
import org.apache.http.*;
import org.apache.http.impl.DefaultBHttpClientConnection;
import org.apache.http.impl.DefaultBHttpServerConnection;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.protocol.UriHttpRequestHandlerMapper;

/**
 * Elemental HTTP/1.1 reverse proxy.
 */
public class SimpleReverseProxy {

    private static final String HTTP_IN_CONN = "http.proxy.in-conn";
    private static final String HTTP_OUT_CONN = "http.proxy.out-conn";
    private static final String HTTP_CONN_KEEPALIVE = "http.proxy.conn-keepalive";

    private static final String HTTP_REQUEST_URI = "http.proxy.request.uri";
    private static final String HTTP_REQUEST_START_TIME = "http.proxy.request.start_time";

    private static final StatusCodeTracker statusCodeTracker = new StatusCodeTracker();
    private static final ResponseTimeTracker timeTracker = new ResponseTimeTracker();


    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: <hostname[:port]> [listener port]");
            System.exit(1);
        }
        final HttpHost targetHost = new HttpHost(args[0]);
        int port = 8080;
        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        }

        System.out.println("Reverse proxy to " + targetHost);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(new StatsRunner(Arrays.asList(statusCodeTracker, timeTracker)),
                10L, 10L, TimeUnit.SECONDS);

        final Thread t = new RequestListenerThread(port, targetHost, statusCodeTracker, timeTracker);
        t.setDaemon(false);
        t.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run()
            {
                System.out.println("Shutdown Hook is running !");
                executor.shutdown();
            }
        });
    }

    static class ProxyHandler implements HttpRequestHandler  {

        private HttpHost target;
        private final HttpProcessor httpproc;
        private final HttpRequestExecutor httpexecutor;
        private final ConnectionReuseStrategy connStrategy;

        public ProxyHandler(
                final HttpHost target,
                final HttpProcessor httpproc,
                final HttpRequestExecutor httpexecutor) {
            super();
            this.target = target;
            this.httpproc = httpproc;
            this.httpexecutor = httpexecutor;
            this.connStrategy = DefaultConnectionReuseStrategy.INSTANCE;
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {

            final DefaultBHttpClientConnection conn = (DefaultBHttpClientConnection) context.getAttribute(
                    HTTP_OUT_CONN);

            if (!conn.isOpen() || conn.isStale()) {
                final Socket outsocket = new Socket(this.target.getHostName(), this.target.getPort() >= 0 ? this.target.getPort() : 80);
                conn.bind(outsocket);
                System.out.println("Outgoing connection to " + outsocket.getInetAddress());
            }

            context.setAttribute(HttpCoreContext.HTTP_CONNECTION, conn);
            context.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, this.target);

            String uri = request.getRequestLine().getUri();
            System.out.println(">> Request URI: " + uri);

            // Remove hop-by-hop headers
            request.removeHeaders(HTTP.TARGET_HOST);
            request.removeHeaders(HTTP.CONTENT_LEN);
            request.removeHeaders(HTTP.TRANSFER_ENCODING);
            request.removeHeaders(HTTP.CONN_DIRECTIVE);
            request.removeHeaders("Keep-Alive");
            request.removeHeaders("Proxy-Authenticate");
            request.removeHeaders("TE");
            request.removeHeaders("Trailers");
            request.removeHeaders("Upgrade");

            this.httpexecutor.preProcess(request, this.httpproc, context);

            final HttpResponse targetResponse = this.httpexecutor.execute(request, conn, context);

            this.httpexecutor.postProcess(response, this.httpproc, context);

            // Remove hop-by-hop headers
            targetResponse.removeHeaders(HTTP.CONTENT_LEN);
            targetResponse.removeHeaders(HTTP.TRANSFER_ENCODING);
            targetResponse.removeHeaders(HTTP.CONN_DIRECTIVE);
            targetResponse.removeHeaders("Keep-Alive");
            targetResponse.removeHeaders("TE");
            targetResponse.removeHeaders("Trailers");
            targetResponse.removeHeaders("Upgrade");

            response.setStatusLine(targetResponse.getStatusLine());
            response.setHeaders(targetResponse.getAllHeaders());
            response.setEntity(targetResponse.getEntity());

            System.out.println("<< Response: " + response.getStatusLine());

            final boolean keepalive = this.connStrategy.keepAlive(response, context);
            context.setAttribute(HTTP_CONN_KEEPALIVE, new Boolean(keepalive));
            context.setAttribute(HTTP_REQUEST_URI, request.getRequestLine().getUri());
        }
    }

    static class RequestListenerThread extends Thread {

        private final HttpHost target;
        private final ServerSocket serversocket;
        private final HttpService httpService;

        public RequestListenerThread(final int port, final HttpHost target,
                                     final StatusCodeTracker statusCodeTracker,
                                     final ResponseTimeTracker uriResponseTime) throws IOException {
            this.target = target;
            this.serversocket = new ServerSocket(port);

            // Set up HTTP protocol processor for incoming connections
            final HttpProcessor inhttpproc = new ImmutableHttpProcessor(
                    new ResponseDate(),
                    new ResponseServer("Test/1.1"),
                    new ResponseContent(),
                    new ResponseConnControl(),
                    statusCodeTracker,
                    uriResponseTime);

            // Set up HTTP protocol processor for outgoing connections
            final HttpProcessor outhttpproc = new ImmutableHttpProcessor(
                    new RequestContent(),
                    new RequestTargetHost(),
                    new RequestConnControl(),
                    new RequestUserAgent("Test/1.1"),
                    new RequestExpectContinue(true));

            // Set up outgoing request executor
            final HttpRequestExecutor httpexecutor = new HttpRequestExecutor();

            // Set up incoming request handler
            final UriHttpRequestHandlerMapper reqistry = new UriHttpRequestHandlerMapper();
            reqistry.register("*", new ProxyHandler(
                    this.target,
                    outhttpproc,
                    httpexecutor));

            // Set up the HTTP service
            this.httpService = new HttpService(inhttpproc, reqistry);
        }

        @Override
        public void run() {
            System.out.println("Listening on port " + this.serversocket.getLocalPort());
            while (!Thread.interrupted()) {
                try {
                    final int bufsize = 8 * 1024;
                    // Set up incoming HTTP connection
                    final Socket insocket = this.serversocket.accept();
                    final DefaultBHttpServerConnection inconn = new DefaultBHttpServerConnection(bufsize);
                    System.out.println("Incoming connection from " + insocket.getInetAddress());
                    inconn.bind(insocket);

                    // Set up outgoing HTTP connection
                    final DefaultBHttpClientConnection outconn = new DefaultBHttpClientConnection(bufsize);

                    // Start worker thread
                    final Thread t = new ProxyThread(this.httpService, inconn, outconn);
                    t.setDaemon(true);
                    t.start();
                } catch (final InterruptedIOException ex) {
                    break;
                } catch (final IOException e) {
                    System.err.println("I/O error initialising connection thread: "
                            + e.getMessage());
                    break;
                }
            }
        }
    }

    static class ProxyThread extends Thread {

        private final HttpService httpservice;
        private final DefaultBHttpServerConnection inconn;
        private final DefaultBHttpClientConnection outconn;

        public ProxyThread(
                final HttpService httpservice,
                final DefaultBHttpServerConnection inconn,
                final DefaultBHttpClientConnection outconn) {
            super();
            this.httpservice = httpservice;
            this.inconn = inconn;
            this.outconn = outconn;
        }

        @Override
        public void run() {
            System.out.println("New connection thread");
            final HttpContext context = new BasicHttpContext(null);

            // Bind connection objects to the execution context
            context.setAttribute(HTTP_IN_CONN, this.inconn);
            context.setAttribute(HTTP_OUT_CONN, this.outconn);

            try {
                while (!Thread.interrupted()) {
                    if (!this.inconn.isOpen()) {
                        this.outconn.close();
                        break;
                    }

                    context.setAttribute(HTTP_REQUEST_START_TIME, System.nanoTime());

                    this.httpservice.handleRequest(this.inconn, context);

                    final Boolean keepalive = (Boolean) context.getAttribute(HTTP_CONN_KEEPALIVE);
                    if (!Boolean.TRUE.equals(keepalive)) {
                        this.outconn.close();
                        this.inconn.close();
                        break;
                    }
                }
            } catch (final ConnectionClosedException ex) {
                System.err.println("Client closed connection");
            } catch (final IOException ex) {
                System.err.println("I/O error: " + ex.getMessage());
            } catch (final HttpException ex) {
                System.err.println("Unrecoverable HTTP protocol violation: " + ex.getMessage());
            } finally {
                try {
                    this.inconn.shutdown();
                } catch (final IOException ignore) {}
                try {
                    this.outconn.shutdown();
                } catch (final IOException ignore) {}
            }
        }
    }

    interface Tracker {
        void dumpStats();
    }

    static class StatsRunner implements Runnable {
        private final List<Tracker> trackers;

        public StatsRunner(List<Tracker> trackers) {
            this.trackers = trackers;
        }

        @Override
        public void run() {
            try {
                for (Tracker tracker : trackers) {
                    tracker.dumpStats();
                }
            } catch (Exception e) {
                System.err.println("Error in executing statistics dumper. It will no longer be run!");
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    static class StatusCodeTracker implements HttpResponseInterceptor, Tracker {
        // Key is URI and value is map of status code and corresponding counter.
        private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, LongAdder>> uriStatusCodeFrequenyCount = new ConcurrentHashMap<>();

        public void process(HttpResponse response, HttpContext context) throws HttpException, IOException {
            System.out.println("Running status code interceptor");
            String uri = (String)context.getAttribute(HTTP_REQUEST_URI);
            Integer statusCode = response.getStatusLine().getStatusCode();

            uriStatusCodeFrequenyCount.computeIfAbsent(uri, v -> new ConcurrentHashMap<>())
                    .computeIfAbsent(statusCode, k -> new LongAdder()).increment();
        }

        public void dumpStats() {
            System.out.println("Dumping status code statistics...");
            for (ConcurrentHashMap.Entry<String, ConcurrentHashMap<Integer, LongAdder>> pair : uriStatusCodeFrequenyCount.entrySet()) {
                String uri = pair.getKey();
                for (ConcurrentHashMap.Entry<Integer, LongAdder> statusCodeCount : pair.getValue().entrySet()) {
                    System.out.println("URI: " + uri + " status: " + statusCodeCount.getKey() + " count: " + statusCodeCount.getValue());
                }
            }
        }
    }

    static class ResponseTimeTracker implements HttpResponseInterceptor, Tracker {
        // Key is URI and value is list of unsorted time duration for each URI for quick addition.
        private final ConcurrentHashMap<String, List<Long>> uriResponseTime = new ConcurrentHashMap<>();
        private static final List<Integer> PERCENTILES = ImmutableList.of(25, 50, 75, 90, 99);
        private static final String EXECUTION_TIME_HEADER_KEY = "X-execution.time";


        public void process(HttpResponse response, HttpContext context) throws HttpException, IOException {
            System.out.println("Running response time interceptor");

            String uri = (String)context.getAttribute(HTTP_REQUEST_URI);
            Long startTime = (Long)context.getAttribute(HTTP_REQUEST_START_TIME);
            Long durationMillisecs = (System.nanoTime() - startTime) / 1000000L;

            add(uri, durationMillisecs);

            response.addHeader(EXECUTION_TIME_HEADER_KEY, String.valueOf(durationMillisecs));
        }

        public void add(String uri, Long durationMillisecs) {
            uriResponseTime.computeIfAbsent(uri, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(durationMillisecs);
        }

        public void dumpStats() {
            System.out.println("Dumping response time statistcs...");
            for (ConcurrentHashMap.Entry<String, List<Long>> pair : uriResponseTime.entrySet()) {
                String uri = pair.getKey();
                List<Long> timeDurations = pair.getValue();
                synchronized (timeDurations) {
                    Collections.sort(timeDurations);
                }
                double multFactor = timeDurations.size() / 100.0;
                for (Integer percentile : PERCENTILES) {
                    int idx = (int)(percentile * multFactor);
                    System.out.println("URI: " + uri + " " + percentile + "th percentile response time " + timeDurations.get(idx) + " millisecs");
                }
            }

        }
    }
}


