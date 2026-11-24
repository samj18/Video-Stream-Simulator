package com.streamsim.service;

import com.streamsim.capture.CaptureSourceFactory;
import com.streamsim.capture.DeviceService;
import com.streamsim.capture.FramePixels;
import com.streamsim.capture.I420Converter;
import com.streamsim.config.StreamSimProperties;
import com.streamsim.model.OutputProtocol;
import com.streamsim.model.PublishConfig;
import com.streamsim.model.SourceType;
import com.streamsim.net.H264RtpStream;
import com.streamsim.net.RtspServer;
import com.streamsim.net.SdpBuilder;
import com.streamsim.net.UdpRtpOutput;
import com.streamsim.web.HttpMediaServer;
import com.streamsim.webrtc.WebRtcHub;
import jakarta.annotation.PreDestroy;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Publishes one source in one of four ways:
 * <ul>
 *   <li>RTSP / RTP: capture -> H.264 (FFmpeg) -> RTP packets -> RTSP server or UDP push</li>
 *   <li>WebRTC: capture -> I420 -> libwebrtc (encodes per viewer) -> browsers via WHEP</li>
 *   <li>HTTP: a local video file served as-is with Range request support (no re-encoding)</li>
 * </ul>
 * One stream at a time. All {@link Listener} callbacks come from the worker thread.
 */
@Service
public class StreamPublisherService {

    private static final Logger log = LoggerFactory.getLogger(StreamPublisherService.class);

    public interface Listener {
        /** Live preview of what is being sent (about 10 fps). */
        void onPreview(FramePixels frame);

        void onStatus(String text);

        void onStats(PublishStats stats);

        /** @param url connection URL (RTSP / WebRTC page / HTTP file) or null; @param sdp SDP file content (RTP mode) or null */
        void onStreamInfo(String url, String sdp);

        /** Always called once when the worker ends. @param error null when stopped normally */
        void onStopped(String error);
    }

    /** @param encoder short description, e.g. "libx264", "WebRTC", "HTTP Range"; width/height 0 = n/a */
    public record PublishStats(double fps, double kbps, int clients, String encoder, int width, int height) {
    }

    private final StreamSimProperties props;
    private final DeviceService devices;
    private volatile Worker worker;

    public StreamPublisherService(StreamSimProperties props, DeviceService devices) {
        this.props = props;
        this.devices = devices;
    }

    public boolean isRunning() {
        Worker w = worker;
        return w != null && w.isAlive();
    }

    public synchronized void start(PublishConfig config, Listener listener) {
        if (isRunning()) {
            throw new IllegalStateException("Already publishing");
        }
        worker = new Worker(config, listener);
        worker.start();
    }

    /** Asynchronous: {@link Listener#onStopped} fires when everything is released. */
    public void stop() {
        Worker w = worker;
        if (w != null) {
            w.cancel();
        }
    }

    @PreDestroy
    void shutdown() {
        Worker w = worker;
        if (w != null) {
            w.cancel();
            try {
                w.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private interface FrameHandler {
        void accept(Frame frame, long nowNanos) throws Exception;
    }

    private interface StatsSource {
        PublishStats sample(double seconds);
    }

    private final class Worker extends Thread {
        private final PublishConfig cfg;
        private final Listener listener;
        private volatile boolean cancelled;

        Worker(PublishConfig cfg, Listener listener) {
            super("publisher-worker");
            setDaemon(true);
            this.cfg = cfg;
            this.listener = listener;
        }

        void cancel() {
            cancelled = true;
        }

        @Override
        public void run() {
            String error = null;
            try {
                switch (cfg.protocol()) {
                    case HTTP -> runHttpFile();
                    case WEBRTC -> runWebRtc();
                    default -> runEncoded();
                }
            } catch (Throwable t) {
                log.error("Publisher failed", t);
                error = describe(t);
            } finally {
                listener.onStatus(error == null ? "Stopped" : "Stopped: " + error);
                listener.onStopped(error);
            }
        }

        // ---------------------------------------------------------------- RTSP / RTP

        private void runEncoded() throws Exception {
            FFmpegFrameGrabber grabber = null;
            FFmpegFrameRecorder recorder = null;
            RtspServer rtsp = null;
            UdpRtpOutput udp = null;
            H264RtpStream stream = new H264RtpStream(props.rtpMaxPayload());
            try {
                listener.onStatus("Opening source...");
                grabber = openGrabber();
                int outW = even(cfg.width() > 0 ? cfg.width() : grabber.getImageWidth());
                int outH = even(cfg.height() > 0 ? cfg.height() : grabber.getImageHeight());
                int fps = Math.max(1, cfg.fps());

                listener.onStatus("Starting H.264 encoder " + outW + "x" + outH + " ...");
                AuCollector collector = new AuCollector();
                EncoderChoice enc = openEncoder(collector, outW, outH, fps, cfg.bitrateKbps());
                recorder = enc.recorder;
                final FFmpegFrameRecorder rec = recorder;

                if (cfg.protocol() == OutputProtocol.RTSP) {
                    rtsp = new RtspServer(stream, InetAddress.getByName(cfg.host()), cfg.port(), cfg.rtspPath());
                    rtsp.start();
                    String url = "rtsp://" + shownHost() + ":" + cfg.port() + "/" + cfg.rtspPath().replaceAll("^/+", "");
                    listener.onStreamInfo(url, null);
                    listener.onStatus("RTSP server ready: " + url);
                } else {
                    udp = new UdpRtpOutput(InetAddress.getByName(cfg.host()), cfg.port());
                    stream.addOutput(udp);
                    listener.onStatus("Sending RTP to " + cfg.host() + ":" + cfg.port());
                }
                final RtspServer rtspRef = rtsp;

                long clockBase = System.nanoTime();
                long rtpBase = (long) (Math.random() * 0x7FFFFFFFL);
                AtomicBoolean sdpPublished = new AtomicBoolean(cfg.protocol() == OutputProtocol.RTSP);
                long[] last = new long[2];

                grabLoop(grabber, sourceFps(grabber, fps), (frame, now) -> {
                    long ts90k = rtpBase + (now - clockBase) * 9L / 100_000L;
                    rec.record(frame);
                    byte[] au = collector.take();
                    if (au != null) {
                        stream.submitAccessUnit(au, au.length, ts90k);
                        if (sdpPublished.compareAndSet(false, true)) {
                            listener.onStreamInfo(null, SdpBuilder.forRtpPush(cfg.host(), cfg.port(),
                                    SdpBuilder.isMulticast(cfg.host()), stream.fmtp()));
                        }
                    }
                }, secs -> {
                    long f = stream.framesSent();
                    long b = stream.bytesSent();
                    PublishStats s = new PublishStats((f - last[0]) / secs, (b - last[1]) * 8 / 1000.0 / secs,
                            rtspRef != null ? rtspRef.clientCount() : 1, enc.name, outW, outH);
                    last[0] = f;
                    last[1] = b;
                    return s;
                });
            } finally {
                stream.closeAllOutputs();
                if (rtsp != null) {
                    rtsp.close();
                }
                if (udp != null) {
                    udp.close();
                }
                if (recorder != null) {
                    try {
                        recorder.stop();
                        recorder.release();
                    } catch (Exception ignored) {
                    }
                }
                closeQuietly(grabber);
            }
        }

        // ---------------------------------------------------------------- WebRTC

        private void runWebRtc() throws Exception {
            FFmpegFrameGrabber grabber = null;
            WebRtcHub hub = null;
            HttpMediaServer http = null;
            try {
                listener.onStatus("Opening source...");
                grabber = openGrabber();
                int outW = even(cfg.width() > 0 ? cfg.width() : grabber.getImageWidth());
                int outH = even(cfg.height() > 0 ? cfg.height() : grabber.getImageHeight());
                int fps = Math.max(1, cfg.fps());

                listener.onStatus("Starting WebRTC...");
                hub = new WebRtcHub();                       // loads the native WebRTC library
                http = new HttpMediaServer(InetAddress.getByName(cfg.host()), cfg.port());
                http.enableWebRtc(hub);
                http.start();
                String url = "http://" + shownHost() + ":" + cfg.port() + "/";
                listener.onStreamInfo(url, null);
                listener.onStatus("WebRTC ready - open " + url + " in a browser");

                final WebRtcHub h = hub;
                byte[] y = new byte[outW * outH];
                byte[] u = new byte[outW * outH / 4];
                byte[] v = new byte[outW * outH / 4];
                long[] pushed = {0, 0};

                grabLoop(grabber, sourceFps(grabber, fps), (frame, now) -> {
                    if (h.viewerCount() == 0 || !(frame.image[0] instanceof ByteBuffer bb)) {
                        return;                              // nobody watching: skip the pixel conversion
                    }
                    I420Converter.convert(bb, frame.imageWidth, frame.imageHeight, frame.imageStride,
                            frame.imageChannels, outW, outH, y, u, v);
                    h.pushI420(y, u, v, outW, outH);
                    pushed[0]++;
                }, secs -> {
                    double f = (pushed[0] - pushed[1]) / secs;
                    pushed[1] = pushed[0];
                    return new PublishStats(f, 0, h.viewerCount(), "WebRTC", outW, outH);
                });
            } finally {
                if (http != null) {
                    http.close();
                }
                if (hub != null) {
                    try {
                        hub.close();
                    } catch (Exception ignored) {
                    }
                }
                closeQuietly(grabber);
            }
        }

        // ---------------------------------------------------------------- HTTP Range file server

        private void runHttpFile() throws Exception {
            if (cfg.source() != SourceType.FILE || cfg.filePath() == null || cfg.filePath().isBlank()) {
                throw new IOException("HTTP mode serves a video file: choose Source = Video file.");
            }
            Path file = Path.of(cfg.filePath().trim());
            if (!Files.isRegularFile(file)) {
                throw new IOException("HTTP mode needs a local file (not a URL): " + file);
            }
            try (HttpMediaServer http = new HttpMediaServer(InetAddress.getByName(cfg.host()), cfg.port())) {
                http.serveFile(file);
                http.start();
                String base = "http://" + shownHost() + ":" + cfg.port();
                listener.onStreamInfo(base + "/video", null);
                listener.onStatus("Serving " + file.getFileName() + " (" + Files.size(file) / 1024 + " KB) with Range requests: "
                        + base + "/video");
                long lastBytes = 0;
                long lastAt = System.nanoTime();
                while (!cancelled) {
                    sleepInterruptibly(1000);
                    long now = System.nanoTime();
                    long b = http.bytesServed();
                    double secs = Math.max(0.001, (now - lastAt) / 1e9);
                    listener.onStats(new PublishStats(0, (b - lastBytes) * 8 / 1000.0 / secs,
                            http.activeRequests(), "HTTP Range", 0, 0));
                    lastBytes = b;
                    lastAt = now;
                }
            }
        }

        // ---------------------------------------------------------------- shared capture loop

        /**
         * Grabs frames until cancelled: loops / ends files, paces files in real time, feeds the handler,
         * emits a ~10 fps preview and a stats line every second.
         */
        private void grabLoop(FFmpegFrameGrabber grabber, double sourceFps, FrameHandler handler, StatsSource stats)
                throws Exception {
            boolean isFile = cfg.source() == SourceType.FILE;
            long frameIntervalNs = (long) (1_000_000_000L / sourceFps);
            long nextDue = System.nanoTime();
            long lastPreview = 0;
            long statsAt = System.nanoTime();

            while (!cancelled) {
                Frame frame = grabber.grabImage();
                if (frame == null || frame.image == null) {
                    if (isFile && cfg.loopFile() && !cancelled) {
                        try {
                            grabber.setTimestamp(0);
                        } catch (Exception e) {
                            grabber.restart();
                        }
                        nextDue = System.nanoTime();
                        continue;
                    }
                    if (isFile) {
                        break;                               // end of file
                    }
                    continue;                                // device hiccup
                }
                if (isFile) {                                // files decode faster than real time
                    nextDue += frameIntervalNs;
                    long wait = nextDue - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    } else if (wait < -500_000_000L) {
                        nextDue = System.nanoTime();
                    }
                }
                long now = System.nanoTime();
                handler.accept(frame, now);

                if (now - lastPreview > 100_000_000L) {
                    lastPreview = now;
                    FramePixels px = FramePixels.from(frame, 960);
                    if (px != null) {
                        listener.onPreview(px);
                    }
                }
                if (now - statsAt >= 1_000_000_000L) {
                    listener.onStats(stats.sample((now - statsAt) / 1e9));
                    statsAt = now;
                }
            }
        }

        private double sourceFps(FFmpegFrameGrabber grabber, int configuredFps) {
            if (cfg.source() == SourceType.FILE && grabber.getFrameRate() > 1) {
                return grabber.getFrameRate();
            }
            return configuredFps;
        }

        private String shownHost() {
            return cfg.host().equals("0.0.0.0") ? devices.primaryAddress() : cfg.host();
        }

        private void sleepInterruptibly(long ms) {
            long end = System.currentTimeMillis() + ms;
            while (!cancelled && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** Tries with framerate/size hints first, then without (some devices reject unsupported modes). */
        private FFmpegFrameGrabber openGrabber() throws Exception {
            Exception first = null;
            for (boolean hints : new boolean[]{true, false}) {
                FFmpegFrameGrabber g = CaptureSourceFactory.create(cfg, hints);
                try {
                    g.start();
                    return g;
                } catch (Exception e) {
                    if (first == null) {
                        first = e;
                    }
                    try {
                        g.release();
                    } catch (Exception ignored) {
                    }
                    if (cfg.source() == SourceType.FILE) {
                        break;                      // hints do not apply to files
                    }
                }
            }
            throw new IOException("Cannot open " + cfg.source() + " source: " + first.getMessage(), first);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static void closeQuietly(FFmpegFrameGrabber g) {
        if (g != null) {
            try {
                g.stop();
                g.release();
            } catch (Exception ignored) {
            }
        }
    }

    private record EncoderChoice(FFmpegFrameRecorder recorder, String name) {
    }

    /** libx264 first; falls back to other H.264 encoders that FFmpeg may have (hardware, openh264...). */
    private EncoderChoice openEncoder(OutputStream sink, int w, int h, int fps, int kbps) throws IOException {
        List<String> candidates = List.of("libx264", "libopenh264", "h264_nvenc", "h264_qsv", "h264_amf",
                "h264_videotoolbox", "h264_mf");
        Exception last = null;
        for (String codec : candidates) {
            FFmpegFrameRecorder r = new FFmpegFrameRecorder(sink, w, h, 0);
            try {
                r.setFormat("h264");                                  // raw Annex-B elementary stream
                r.setVideoCodecName(codec);
                r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
                r.setFrameRate(fps);
                r.setVideoBitrate(Math.max(100, kbps) * 1000);
                r.setGopSize(fps);                                    // one IDR per second -> fast join
                r.setOption("flush_packets", "1");                    // hand every frame to us immediately
                if (codec.equals("libx264")) {
                    r.setVideoOption("preset", "ultrafast");
                    r.setVideoOption("tune", "zerolatency");
                    r.setVideoOption("profile", "baseline");          // plays everywhere (VLC, browsers, phones)
                }
                r.start();
                log.info("Using H.264 encoder {}", codec);
                return new EncoderChoice(r, codec);
            } catch (Exception e) {
                last = e;
                log.debug("Encoder {} not available: {}", codec, e.toString());
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }
        throw new IOException("No H.264 encoder available in this FFmpeg build (last error: "
                + (last == null ? "n/a" : last.getMessage()) + ")", last);
    }

    private static int even(int v) {
        return Math.max(2, v & ~1);
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    /** Collects what the encoder writes for ONE frame; flush_packets=1 makes each frame arrive complete. */
    private static final class AuCollector extends OutputStream {
        private byte[] buf = new byte[1 << 18];
        private int len;

        @Override
        public synchronized void write(int b) {
            ensure(1);
            buf[len++] = (byte) b;
        }

        @Override
        public synchronized void write(byte[] b, int off, int n) {
            ensure(n);
            System.arraycopy(b, off, buf, len, n);
            len += n;
        }

        private void ensure(int extra) {
            if (len + extra > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
            }
        }

        synchronized byte[] take() {
            if (len == 0) {
                return null;
            }
            byte[] out = Arrays.copyOf(buf, len);
            len = 0;
            return out;
        }
    }
}
