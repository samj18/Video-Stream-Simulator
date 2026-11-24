package com.streamsim.service;

import com.streamsim.capture.FramePixels;
import com.streamsim.model.ReceiveConfig;
import com.streamsim.model.ReceiveProtocol;
import com.streamsim.net.SdpBuilder;
import com.streamsim.webrtc.WhepReceiver;
import jakarta.annotation.PreDestroy;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Receives a stream with FFmpeg (RTSP / RTP / any URL) and hands decoded frames to the UI.
 * Works with this app on another PC, VLC ("Stream" / "Convert" output), ffmpeg, IP cameras ...
 */
@Service
public class StreamReceiverService {

    private static final Logger log = LoggerFactory.getLogger(StreamReceiverService.class);

    public interface Listener {
        void onFrame(FramePixels frame);

        void onStatus(String text);

        /** Playback position of a seekable stream (~5x per second). Not called for live streams. */
        default void onPosition(long positionUs, long durationUs) {
        }

        /** Always called once when the worker ends. @param error null when stopped normally */
        void onStopped(String error);
    }

    private volatile Worker worker;

    public boolean isRunning() {
        Worker w = worker;
        return w != null && w.isAlive();
    }

    public synchronized void start(ReceiveConfig config, Listener listener) {
        if (isRunning()) {
            throw new IllegalStateException("Already receiving");
        }
        worker = new Worker(config, listener);
        worker.start();
    }

    public void setPaused(boolean paused) {
        Worker w = worker;
        if (w != null) {
            w.paused = paused;
        }
    }

    public boolean isPaused() {
        Worker w = worker;
        return w != null && w.paused;
    }

    /** Relative seek (negative = backward). Only has an effect on seekable streams (HTTP / file). */
    public void seekBy(long deltaMs) {
        Worker w = worker;
        if (w != null) {
            w.requestSeek(true, deltaMs * 1000L);
        }
    }

    /** Absolute seek to a position in milliseconds. */
    public void seekTo(long positionMs) {
        Worker w = worker;
        if (w != null) {
            w.requestSeek(false, positionMs * 1000L);
        }
    }

    /** Asynchronous: {@link Listener#onStopped} fires when the connection is closed (a few seconds at most). */
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

    private static final class Worker extends Thread {
        private final ReceiveConfig cfg;
        private final Listener listener;
        private volatile boolean cancelled;
        volatile boolean paused;
        private Path sdpFile;
        private boolean seekPending;
        private boolean seekRelative;
        private long seekValueUs;

        Worker(ReceiveConfig cfg, Listener listener) {
            super("receiver-worker");
            setDaemon(true);
            this.cfg = cfg;
            this.listener = listener;
        }

        void cancel() {
            cancelled = true;
        }

        synchronized void requestSeek(boolean relative, long us) {
            if (relative && seekPending && seekRelative) {
                seekValueUs += us;                 // quick repeated clicks add up
            } else {
                seekPending = true;
                seekRelative = relative;
                seekValueUs = us;
            }
        }

        /** @return the absolute target in microseconds, or -1 when no seek is pending */
        private synchronized long takeSeek(long currentUs, long durationUs) {
            if (!seekPending) {
                return -1;
            }
            seekPending = false;
            long t = seekRelative ? currentUs + seekValueUs : seekValueUs;
            long max = durationUs > 0 ? Math.max(0, durationUs - 500_000L) : Long.MAX_VALUE;
            return Math.max(0, Math.min(t, max));
        }

        private synchronized void clearSeek() {
            seekPending = false;
        }

        @Override
        public void run() {
            String error = null;
            try {
                while (!cancelled) {
                    try {
                        receiveOnce();
                        if (!cancelled) {
                            listener.onStatus("Stream ended");
                        }
                    } catch (Exception e) {
                        if (cancelled) {
                            break;
                        }
                        log.warn("Receive error: {}", e.toString());
                        if (!cfg.autoReconnect()) {
                            error = e.getMessage() == null ? e.toString() : e.getMessage();
                            break;
                        }
                        listener.onStatus("No stream (" + shorten(e.getMessage()) + ") - retrying...");
                    }
                    if (!cfg.autoReconnect()) {
                        break;
                    }
                    sleepInterruptibly(1500);
                }
            } catch (Throwable t) {
                log.error("Receiver failed", t);
                error = t.getMessage() == null ? t.toString() : t.getMessage();
            } finally {
                if (sdpFile != null) {
                    try {
                        Files.deleteIfExists(sdpFile);
                    } catch (IOException ignored) {
                    }
                }
                listener.onStatus(error == null ? "Stopped" : "Stopped: " + error);
                listener.onStopped(error);
            }
        }

        private void receiveOnce() throws Exception {
            clearSeek();
            if (cfg.protocol() == ReceiveProtocol.WEBRTC) {
                receiveWebRtc();
                return;
            }
            FFmpegFrameGrabber g = createGrabber();
            long lastUi = 0;
            long lastPos = 0;
            try {
                listener.onStatus("Connecting...");
                g.start();
                final long durationUs = cfg.protocol() == ReceiveProtocol.HTTP ? g.getLengthInTime() : 0;
                final boolean seekable = durationUs > 0;
                String info = g.getImageWidth() + "x" + g.getImageHeight()
                        + (g.getVideoCodecName() != null ? " (" + g.getVideoCodecName() + ")" : "");
                listener.onStatus((seekable ? "Playing " : "Receiving ") + info);

                long baseTs = -1;          // pacing: stream time <-> wall clock (seekable streams only)
                long baseWall = 0;
                long currentUs = 0;
                boolean showNext = false;  // after a seek while paused: still show one frame
                while (!cancelled) {
                    if (seekable) {
                        long target = takeSeek(currentUs, durationUs);
                        if (target >= 0) {
                            g.setTimestamp(target);
                            currentUs = target;
                            baseTs = -1;
                            showNext = true;
                        }
                        if (paused && !showNext) {
                            baseTs = -1;
                            reportPosition(currentUs, durationUs);
                            sleepInterruptibly(40);
                            continue;
                        }
                    }
                    Frame f = g.grabImage();
                    if (f == null) {
                        if (seekable) {
                            reportPosition(durationUs, durationUs);
                        }
                        return;                       // end of stream
                    }
                    if (seekable) {
                        currentUs = f.timestamp;
                        if (!showNext) {
                            if (baseTs < 0) {
                                baseTs = f.timestamp;
                                baseWall = System.nanoTime();
                            }
                            if (!paceUntil(baseWall + (f.timestamp - baseTs) * 1000L)) {
                                continue;            // a seek / pause arrived while waiting
                            }
                        }
                        showNext = false;
                        long now = System.nanoTime();
                        if (now - lastPos >= 200_000_000L) {
                            lastPos = now;
                            reportPosition(currentUs, durationUs);
                        }
                    }
                    long now = System.nanoTime();
                    if (now - lastUi >= 30_000_000L || seekable) {   // max ~33 fps to the UI
                        lastUi = now;
                        FramePixels px = FramePixels.from(f, 1280);
                        if (px != null) {
                            listener.onFrame(px);
                        }
                    }
                }
            } finally {
                try {
                    g.stop();
                } catch (Exception ignored) {
                }
                try {
                    g.release();
                } catch (Exception ignored) {
                }
            }
        }

        private void reportPosition(long posUs, long durUs) {
            listener.onPosition(posUs, durUs);
        }

        /**
         * Waits until the wall-clock deadline (nanoTime) in small slices.
         *
         * @return false when interrupted by a pending seek, pause or cancel (the frame is then skipped)
         */
        private boolean paceUntil(long deadlineNs) {
            while (!cancelled) {
                long wait = deadlineNs - System.nanoTime();
                if (wait <= 1_000_000L) {
                    return true;
                }
                synchronized (this) {
                    if (seekPending) {
                        return false;
                    }
                }
                if (paused) {
                    return false;
                }
                try {
                    Thread.sleep(Math.min(20, wait / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        private void receiveWebRtc() throws Exception {
            listener.onStatus("Connecting (WebRTC)...");
            final long[] last = {System.nanoTime()};
            final boolean[] first = {true};
            try (WhepReceiver r = WhepReceiver.connect(cfg.url().trim(), px -> {
                last[0] = System.nanoTime();
                if (first[0]) {
                    first[0] = false;
                    listener.onStatus("Receiving WebRTC " + px.width() + "x" + px.height());
                }
                listener.onFrame(px);
            })) {
                listener.onStatus("Connected, waiting for video...");
                while (!cancelled) {
                    Thread.sleep(200);
                    if (System.nanoTime() - last[0] > 10_000_000_000L) {
                        throw new IOException("no video for 10 s");
                    }
                }
            }
        }

        private FFmpegFrameGrabber createGrabber() throws IOException {
            FFmpegFrameGrabber g;
            switch (cfg.protocol()) {
                case RTSP -> {
                    g = new FFmpegFrameGrabber(cfg.url().trim());
                    g.setOption("rtsp_transport", cfg.rtspTcp() ? "tcp" : "udp");
                    g.setOption("allowed_media_types", "video");
                }
                case RTP -> {
                    String sdp = SdpBuilder.forRtpReceive(cfg.listenAddress().trim(), cfg.port());
                    sdpFile = Files.createTempFile("streamsim-recv-", ".sdp");
                    Files.writeString(sdpFile, sdp, StandardCharsets.US_ASCII);
                    g = new FFmpegFrameGrabber(sdpFile.toString());
                    g.setFormat("sdp");
                    g.setOption("protocol_whitelist", "file,udp,rtp,crypto,data");
                    g.setOption("max_delay", "200000");
                }
                case HTTP -> {
                    g = new FFmpegFrameGrabber(cfg.url().trim());
                    g.setOption("reconnect", "1");
                    g.setOption("seekable", "1");
                    g.setOption("timeout", "5000000");
                    return g;                      // no low-delay flags: this is file-style playback
                }
                default -> {
                    String url = cfg.url().trim();
                    g = new FFmpegFrameGrabber(url);
                    if (url.toLowerCase().endsWith(".sdp")) {
                        g.setFormat("sdp");
                        g.setOption("protocol_whitelist", "file,udp,rtp,rtsp,tcp,crypto,data");
                    }
                }
            }
            // low latency + socket timeout (microseconds) so a dead sender never blocks us (or Stop) forever
            g.setOption("fflags", "nobuffer");
            g.setOption("flags", "low_delay");
            g.setOption("timeout", "5000000");
            return g;
        }

        private static String shorten(String m) {
            if (m == null) {
                return "no data";
            }
            return m.length() > 80 ? m.substring(0, 80) + "..." : m;
        }

        private void sleepInterruptibly(long ms) {
            long end = System.currentTimeMillis() + ms;
            while (!cancelled && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
