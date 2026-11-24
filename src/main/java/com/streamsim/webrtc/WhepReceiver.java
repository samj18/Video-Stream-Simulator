package com.streamsim.webrtc;

import com.streamsim.capture.FramePixels;
import com.streamsim.capture.I420ToArgb;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.MediaKind;
import dev.onvoid.webrtc.client.Whep;
import dev.onvoid.webrtc.client.WebRtc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebRTC receiver (WHEP client). The only receive-side class that touches webrtc-java, so the native
 * library is loaded only when the user actually picks the WebRTC receive mode.
 */
public final class WhepReceiver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WhepReceiver.class);

    public interface Sink {
        void onFrame(FramePixels frame);
    }

    private final WebRtc rtc;
    private final Whep.Session session;
    private volatile boolean closed;
    private volatile long lastUi;

    private WhepReceiver(WebRtc rtc, Whep.Session session) {
        this.rtc = rtc;
        this.session = session;
    }

    /** Blocks until the WHEP exchange is done and the peer is connected (max ~20 s). */
    public static WhepReceiver connect(String whepUrl, Sink sink) throws Exception {
        WebRtc rtc = WebRtc.builder()
                .audio(AudioMode.HEADLESS)
                .errorHandler(t -> log.warn("WebRTC error: {}", t.toString()))
                .build();
        try {
            Whep.Session session = Whep.from(URI.create(whepUrl))
                    .receive(MediaKind.VIDEO)
                    .timeout(Duration.ofSeconds(15))
                    .play(rtc)
                    .get(20, TimeUnit.SECONDS);
            WhepReceiver r = new WhepReceiver(rtc, session);
            // Peer#onTrack also delivers tracks that arrived before the handler was added
            session.peer().onTrack(track -> {
                if (track.isVideo()) {
                    track.asVideo().onFrame(frame -> r.handle(frame.buffer.toI420(), sink));
                }
            });
            return r;
        } catch (Exception | Error e) {
            try {
                rtc.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /** Called on a webrtc-java thread; the frame is only valid inside the handler, so convert here. */
    private void handle(I420Buffer i420, Sink sink) {
        try {
            long now = System.nanoTime();
            if (closed || now - lastUi < 30_000_000L) {      // max ~33 fps to the UI
                return;
            }
            lastUi = now;
            sink.onFrame(I420ToArgb.convert(
                    i420.getDataY(), i420.getStrideY(),
                    i420.getDataU(), i420.getStrideU(),
                    i420.getDataV(), i420.getStrideV(),
                    i420.getWidth(), i420.getHeight(), 1280));
        } catch (RuntimeException e) {
            log.debug("frame dropped: {}", e.toString());
        } finally {
            try {
                i420.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            session.close();          // sends the WHEP DELETE and closes the peer
        } catch (Exception e) {
            log.debug("session close: {}", e.toString());
        }
        try {
            rtc.close();
        } catch (Exception e) {
            log.debug("webrtc close: {}", e.toString());
        }
    }
}
