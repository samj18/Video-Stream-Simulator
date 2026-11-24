package com.streamsim.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small RTSP server (RFC 2326) that serves ONE H.264 stream to any number of clients
 * (VLC, ffplay, another instance of this app, ...). Supports RTP over UDP and RTP over TCP (interleaved).
 *
 * URL: rtsp://&lt;ip&gt;:&lt;port&gt;/&lt;path&gt;
 */
public final class RtspServer implements AutoCloseable {

    private static final Pattern INTERLEAVED = Pattern.compile("interleaved=(\\d+)(?:-(\\d+))?");
    private static final Pattern CLIENT_PORT = Pattern.compile("client_port=(\\d+)(?:-(\\d+))?");
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final H264RtpStream stream;
    private final InetAddress bindAddress;
    private final int port;
    private final String path;          // without leading/trailing slash
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;   // shared sender for all UDP clients (its port is the RTSP "server_port")

    public RtspServer(H264RtpStream stream, InetAddress bindAddress, int port, String path) {
        this.stream = stream;
        this.bindAddress = bindAddress;
        this.port = port;
        String p = path == null ? "live" : path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        this.path = p;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port, 32, bindAddress);
        udpSocket = new DatagramSocket();
        udpSocket.setSendBufferSize(1 << 20);
        running = true;
        Thread t = new Thread(this::acceptLoop, "rtsp-accept-" + port);
        t.setDaemon(true);
        t.start();
    }

    public int clientCount() {
        int n = 0;
        for (Connection c : connections) {
            if (c.playing) {
                n++;
            }
        }
        return n;
    }

    public int port() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (Connection c : new ArrayList<>(connections)) {
            c.close();
        }
        if (udpSocket != null) {
            udpSocket.close();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                Connection c = new Connection(s);
                connections.add(c);
                Thread t = new Thread(c, "rtsp-client-" + THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    // transient accept error; keep serving
                    continue;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private final class Connection implements Runnable, RtpOutput {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private final BlockingQueue<List<byte[]>> queue = new ArrayBlockingQueue<>(120);
        private final String sessionId = Long.toHexString(random.nextLong() & 0x7FFFFFFFFFFFL);

        private volatile boolean playing;
        private volatile boolean waitingForKeyFrame = true;
        private volatile boolean closed;
        private boolean tcp;
        private int rtpChannel;
        private InetAddress udpAddress;
        private int udpPort;
        private Thread writer;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
            this.socket.setSoTimeout(120_000);
        }

        @Override
        public void run() {
            try {
                while (!closed) {
                    int b = in.read();
                    if (b < 0) {
                        break;
                    }
                    if (b == '$') {                               // interleaved RTCP from the client: ignore
                        int ch = in.read();
                        int hi = in.read();
                        int lo = in.read();
                        if (ch < 0 || hi < 0 || lo < 0) {
                            break;
                        }
                        in.skipNBytes(((hi << 8) | lo));
                        continue;
                    }
                    String requestLine = readLine(b);
                    if (requestLine.isEmpty()) {
                        continue;
                    }
                    Map<String, String> headers = new LinkedHashMap<>();
                    String line;
                    while (!(line = readLine(in.read())).isEmpty()) {
                        int idx = line.indexOf(':');
                        if (idx > 0) {
                            headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT), line.substring(idx + 1).trim());
                        }
                    }
                    String len = headers.get("content-length");
                    if (len != null) {
                        in.skipNBytes(Integer.parseInt(len.trim()));
                    }
                    handle(requestLine, headers);
                }
            } catch (IOException | RuntimeException e) {
                // client went away
            } finally {
                close();
            }
        }

        private String readLine(int first) throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(128);
            int c = first;
            while (c >= 0 && c != '\n') {
                if (c != '\r') {
                    bo.write(c);
                }
                c = in.read();
            }
            if (c < 0 && bo.size() == 0) {
                throw new IOException("EOF");
            }
            return bo.toString(StandardCharsets.ISO_8859_1);
        }

        private void handle(String requestLine, Map<String, String> h) throws IOException {
            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String uri = parts[1];
            String cseq = h.getOrDefault("cseq", "0");

            switch (method) {
                case "OPTIONS" -> reply(cseq, 200, "OK",
                        "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER\r\n", null);
                case "DESCRIBE" -> {
                    if (!pathMatches(uri)) {
                        reply(cseq, 404, "Not Found", "", null);
                        return;
                    }
                    if (!stream.awaitParameterSets(8000)) {
                        reply(cseq, 503, "Service Unavailable", "", null);
                        return;
                    }
                    String base = uri.endsWith("/") ? uri : uri + "/";
                    String sdp = SdpBuilder.forRtsp(stream.fmtp(), socket.getLocalAddress().getHostAddress());
                    reply(cseq, 200, "OK",
                            "Content-Base: " + base + "\r\nContent-Type: application/sdp\r\n", sdp);
                }
                case "SETUP" -> setup(cseq, h);
                case "PLAY" -> {
                    if (udpAddress == null && !tcp) {
                        reply(cseq, 455, "Method Not Valid In This State", "", null);
                        return;
                    }
                    reply(cseq, 200, "OK",
                            "Session: " + sessionId + "\r\nRange: npt=0.000-\r\n", null);
                    startPlaying();
                }
                case "PAUSE" -> {
                    playing = false;
                    reply(cseq, 200, "OK", "Session: " + sessionId + "\r\n", null);
                }
                case "TEARDOWN" -> {
                    reply(cseq, 200, "OK", "Session: " + sessionId + "\r\n", null);
                    close();
                }
                case "GET_PARAMETER", "SET_PARAMETER" ->
                        reply(cseq, 200, "OK", "Session: " + sessionId + "\r\n", null);
                default -> reply(cseq, 501, "Not Implemented", "", null);
            }
        }

        private boolean pathMatches(String uri) {
            String p = uri;
            int scheme = p.indexOf("://");
            if (scheme >= 0) {
                int slash = p.indexOf('/', scheme + 3);
                p = slash >= 0 ? p.substring(slash) : "/";
            }
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            return p.equals(path) || p.startsWith(path + "/");
        }

        private void setup(String cseq, Map<String, String> h) throws IOException {
            String transport = h.getOrDefault("transport", "");
            String lower = transport.toLowerCase(Locale.ROOT);
            if (lower.contains("multicast")) {
                reply(cseq, 461, "Unsupported Transport", "", null);
                return;
            }
            if (lower.contains("rtp/avp/tcp") || lower.contains("interleaved")) {
                tcp = true;
                Matcher m = INTERLEAVED.matcher(lower);
                rtpChannel = m.find() ? Integer.parseInt(m.group(1)) : 0;
                int rtcp = rtpChannel + 1;
                reply(cseq, 200, "OK",
                        "Session: " + sessionId + ";timeout=60\r\n"
                                + "Transport: RTP/AVP/TCP;unicast;interleaved=" + rtpChannel + "-" + rtcp + "\r\n", null);
                return;
            }
            Matcher m = CLIENT_PORT.matcher(lower);
            if (!m.find()) {
                reply(cseq, 461, "Unsupported Transport", "", null);
                return;
            }
            tcp = false;
            udpPort = Integer.parseInt(m.group(1));
            udpAddress = socket.getInetAddress();
            int serverPort = udpSocket.getLocalPort();
            reply(cseq, 200, "OK",
                    "Session: " + sessionId + ";timeout=60\r\n"
                            + "Transport: RTP/AVP;unicast;client_port=" + m.group(1)
                            + (m.group(2) != null ? "-" + m.group(2) : "")
                            + ";server_port=" + serverPort + "-" + (serverPort + 1) + "\r\n", null);
        }

        private void startPlaying() {
            if (writer == null) {
                writer = new Thread(this::writeLoop, "rtsp-writer-" + THREAD_ID.incrementAndGet());
                writer.setDaemon(true);
                writer.start();
            }
            waitingForKeyFrame = true;
            playing = true;
            stream.addOutput(this);
        }

        private void reply(String cseq, int code, String text, String extraHeaders, String body) throws IOException {
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("RTSP/1.0 ").append(code).append(' ').append(text).append("\r\n");
            sb.append("CSeq: ").append(cseq).append("\r\n");
            sb.append("Server: VideoStreamSimulator\r\n");
            sb.append(extraHeaders);
            if (bodyBytes.length > 0) {
                sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            }
            sb.append("\r\n");
            synchronized (writeLock) {
                out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (bodyBytes.length > 0) {
                    out.write(bodyBytes);
                }
                out.flush();
            }
        }

        // ---- RtpOutput: called from the encoder thread, must never block -------------------------

        @Override
        public void accept(List<byte[]> packets, boolean keyFrame) {
            if (!playing || closed) {
                return;
            }
            if (waitingForKeyFrame) {
                if (!keyFrame) {
                    return;
                }
                waitingForKeyFrame = false;
            }
            if (!queue.offer(packets)) {          // slow client: drop and resync on the next IDR
                queue.clear();
                waitingForKeyFrame = true;
            }
        }

        private void writeLoop() {
            byte[] frameHeader = new byte[4];
            try {
                while (!closed) {
                    List<byte[]> packets = queue.take();
                    if (tcp) {
                        synchronized (writeLock) {
                            for (byte[] p : packets) {
                                frameHeader[0] = '$';
                                frameHeader[1] = (byte) rtpChannel;
                                frameHeader[2] = (byte) (p.length >> 8);
                                frameHeader[3] = (byte) p.length;
                                out.write(frameHeader);
                                out.write(p);
                            }
                            out.flush();
                        }
                    } else {
                        for (byte[] p : packets) {
                            udpSocket.send(new DatagramPacket(p, p.length, udpAddress, udpPort));
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SocketException e) {
                // closed
            } catch (IOException e) {
                // client gone
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            playing = false;
            stream.removeOutput(this);
            connections.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (writer != null) {
                writer.interrupt();
            }
        }
    }
}
