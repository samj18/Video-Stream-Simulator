package com.streamsim.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One tiny HTTP server (JDK built-in, no extra dependency) that provides:
 * <ul>
 *   <li><b>HTTP Range</b> file streaming: {@code GET /video} (and {@code /<file name>}) with 206 partial content</li>
 *   <li><b>WebRTC</b> signalling for browsers: WHEP {@code POST /whep}, and a viewer page at {@code /}</li>
 * </ul>
 */
public final class HttpMediaServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService pool;
    private final AtomicLong bytesServed = new AtomicLong();
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final RangeFileResponder ranges = new RangeFileResponder(bytesServed);
    private final SecureRandom random = new SecureRandom();

    private volatile Path file;
    private volatile WebRtcAnswerer webrtc;

    public HttpMediaServer(InetAddress bind, int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-media");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(pool);
        this.server.createContext("/", this::route);
    }

    /** Serve this file at /video with Range support. */
    public void serveFile(Path f) {
        this.file = f;
    }

    /** Enable the WHEP endpoint and the browser viewer page. */
    public void enableWebRtc(WebRtcAnswerer answerer) {
        this.webrtc = answerer;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public long bytesServed() {
        return bytesServed.get();
    }

    public int activeRequests() {
        return activeRequests.get();
    }

    public int viewers() {
        WebRtcAnswerer w = webrtc;
        return w == null ? 0 : w.viewerCount();
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------

    private void route(HttpExchange ex) throws IOException {
        activeRequests.incrementAndGet();
        try {
            // CORS: lets a page from another origin use /video (<video>, fetch with Range) and /whep
            var h = ex.getResponseHeaders();
            h.set("Access-Control-Allow-Origin", "*");
            h.set("Access-Control-Allow-Methods", "GET, HEAD, POST, DELETE, OPTIONS");
            h.set("Access-Control-Allow-Headers", "Range, If-Range, Content-Type");
            h.set("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges, Location");
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            Path f = file;
            if (path.equals("/whep") || path.startsWith("/whep/")) {
                whep(ex, method, path);
            } else if (f != null && (path.equals("/video") || path.equals("/" + f.getFileName()))) {
                if (!Files.isReadable(f)) {
                    text(ex, 404, "File not found: " + f);
                } else {
                    ranges.serve(ex, f);
                }
            } else if (path.equals("/") || path.equals("/index.html")) {
                if (webrtc == null && f == null) {
                    text(ex, 404, "Nothing is being published");
                } else {
                    byte[] page = Pages.index(webrtc != null, f == null ? null : f.getFileName().toString())
                            .getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, method.equals("HEAD") ? -1 : page.length);
                    if (!method.equals("HEAD")) {
                        ex.getResponseBody().write(page);
                    }
                }
            } else {
                text(ex, 404, "Not found");
            }
        } catch (RuntimeException e) {
            try {
                text(ex, 500, "Internal error: " + e);
            } catch (IOException ignored) {
            }
        } finally {
            activeRequests.decrementAndGet();
            ex.close();
        }
    }

    /** WHEP (WebRTC-HTTP Egress Protocol): POST an SDP offer, get 201 + SDP answer. */
    private void whep(HttpExchange ex, String method, String path) throws IOException {
        WebRtcAnswerer w = webrtc;
        if (w == null) {
            text(ex, 404, "WebRTC is not enabled");
            return;
        }
        if (method.equals("DELETE")) {            // viewer left; the peer is cleaned up by its connection state
            ex.sendResponseHeaders(200, -1);
            return;
        }
        if (!method.equals("POST") || !path.equals("/whep")) {
            ex.getResponseHeaders().set("Allow", "POST, DELETE, OPTIONS");
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String offer = new String(ex.getRequestBody().readNBytes(256 * 1024), StandardCharsets.UTF_8);
        if (offer.isBlank() || !offer.startsWith("v=0")) {
            text(ex, 400, "Body must be an SDP offer (Content-Type: application/sdp)");
            return;
        }
        try {
            String answer = w.answer(offer);
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            var h = ex.getResponseHeaders();
            h.set("Content-Type", "application/sdp");
            String host = ex.getRequestHeaders().getFirst("Host");
            String id = "/whep/" + Long.toHexString(random.nextLong() & 0xFFFFFFFFFFFFL);
            h.set("Location", host == null ? id : "http://" + host + id);
            ex.sendResponseHeaders(201, body.length);
            ex.getResponseBody().write(body);
        } catch (Exception e) {
            text(ex, 500, "WebRTC negotiation failed: " + e.getMessage());
        }
    }

    private static void text(HttpExchange ex, int code, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }
}
