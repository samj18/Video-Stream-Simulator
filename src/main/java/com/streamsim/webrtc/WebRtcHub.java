package com.streamsim.webrtc;

import com.streamsim.web.WebRtcAnswerer;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.client.*;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * WebRTC side of the app (library: dev.onvoid.webrtc:webrtc-java 1.0.1) - the ONLY class that touches it.
 *
 * <p>One shared {@link CustomVideo} source is fed with I420 frames; every browser that POSTs an SDP offer to
 * {@code /whep} gets its own {@link Peer} that sends that source (libwebrtc encodes VP8/H.264 and does
 * congestion control per viewer).
 */
public final class WebRtcHub implements WebRtcAnswerer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebRtcHub.class);
    private static final int MAX_VIEWERS = 8;

    private final WebRtc rtc;
    private final CustomVideo video;
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();

    public WebRtcHub() {
        this.rtc = WebRtc.builder()
                .audio(AudioMode.HEADLESS)                   // video only: never open a microphone / speakers
                .errorHandler(e -> log.warn("WebRTC error", e))
                .build();
        this.video = rtc.media().customVideo("simulator");
    }

    @Override
    public String answer(String offerSdp) throws Exception {
        if (peers.size() >= MAX_VIEWERS) {
            throw new IllegalStateException("Too many viewers (max " + MAX_VIEWERS + ")");
        }
        Peer peer = rtc.createPeer(PeerOptions.builder()
                .negotiation(Negotiation.MANUAL)             // WHEP: the offer arrives over plain HTTP
                .build());
        peers.add(peer);
        peer.whenClosed().thenRun(() -> peers.remove(peer));
        peer.onStateChange(state -> {
            String s = String.valueOf(state).toUpperCase();
            if (s.contains("FAILED") || s.contains("CLOSED") || s.contains("DISCONNECTED")) {
                peer.close();                                // browser tab closed / network lost
                peers.remove(peer);
            }
        });
        try {
            peer.addTrack(video);
            RTCSessionDescription offer = new RTCSessionDescription(RTCSdpType.OFFER, offerSdp);
            // COMPLETE: wait for ICE gathering so the answer already carries every candidate (no trickle in WHEP)
            return peer.negotiator().answer(offer, Gathering.COMPLETE)
                    .thenApply(answer -> answer.sdp)
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            peer.close();
            peers.remove(peer);
            throw e;
        }
    }

    @Override
    public int viewerCount() {
        return peers.size();
    }

    /**
     * Pushes one frame (planar I420, tightly packed) to all viewers. Cheap no-op without viewers:
     * check {@link #viewerCount()} before converting pixels.
     */
    public void pushI420(byte[] y, byte[] u, byte[] v, int width, int height) {
        NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
        copyPlane(buffer.getDataY(), buffer.getStrideY(), y, width, height);
        copyPlane(buffer.getDataU(), buffer.getStrideU(), u, width / 2, height / 2);
        copyPlane(buffer.getDataV(), buffer.getStrideV(), v, width / 2, height / 2);
        VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
        try {
            video.push(frame);
        } finally {
            frame.release();
        }
    }

    private static void copyPlane(ByteBuffer dst, int dstStride, byte[] src, int w, int h) {
        ByteBuffer d = dst.duplicate();
        for (int row = 0; row < h; row++) {
            d.position(row * dstStride);
            d.put(src, row * w, w);
        }
    }

    @Override
    public void close() {
        for (Peer p : peers) {
            p.close();
        }
        peers.clear();
        rtc.close();
    }
}
