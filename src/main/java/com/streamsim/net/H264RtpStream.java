package com.streamsim.net;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hub between the encoder and the network: takes encoded access units (Annex-B), packetizes them
 * once and fans the RTP packets out to every registered {@link RtpOutput}.
 */
public final class H264RtpStream {

    private final H264RtpPacketizer packetizer;
    private final List<RtpOutput> outputs = new CopyOnWriteArrayList<>();
    private final CountDownLatch parametersKnown = new CountDownLatch(1);
    private volatile byte[] sps;
    private volatile byte[] pps;

    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong framesSent = new AtomicLong();

    public H264RtpStream(int maxRtpPayload) {
        this.packetizer = new H264RtpPacketizer(maxRtpPayload);
    }

    public void addOutput(RtpOutput o) {
        outputs.add(o);
    }

    public void removeOutput(RtpOutput o) {
        outputs.remove(o);
    }

    public int outputCount() {
        return outputs.size();
    }

    public long packetsSent() {
        return packetsSent.get();
    }

    public long bytesSent() {
        return bytesSent.get();
    }

    public long framesSent() {
        return framesSent.get();
    }

    /** Submit one encoded frame (Annex-B, may contain SPS/PPS/SEI/slices). */
    public void submitAccessUnit(byte[] annexB, int len, long timestamp90k) {
        List<byte[]> src = AnnexB.split(annexB, 0, len);
        if (src.isEmpty()) {
            return;
        }
        List<byte[]> nals = new ArrayList<>(src.size() + 2);
        boolean idr = false;
        boolean hasParams = false;
        for (byte[] nal : src) {
            int t = AnnexB.nalType(nal);
            if (t == AnnexB.NAL_AUD || t == AnnexB.NAL_FILLER) {
                continue;
            }
            if (t == AnnexB.NAL_SPS) {
                sps = nal;
                hasParams = true;
            } else if (t == AnnexB.NAL_PPS) {
                pps = nal;
                if (sps != null) {
                    parametersKnown.countDown();
                }
            } else if (t == AnnexB.NAL_SLICE_IDR) {
                idr = true;
            }
            nals.add(nal);
        }
        if (nals.isEmpty()) {
            return;
        }
        // make every IDR self-contained so late joiners can start decoding
        if (idr && !hasParams && sps != null && pps != null) {
            nals.add(0, pps);
            nals.add(0, sps);
        }
        List<byte[]> packets = packetizer.packetize(nals, timestamp90k & 0xFFFFFFFFL);
        framesSent.incrementAndGet();
        long bytes = 0;
        for (byte[] p : packets) {
            bytes += p.length;
        }
        packetsSent.addAndGet(packets.size());
        bytesSent.addAndGet(bytes);
        for (RtpOutput o : outputs) {
            try {
                o.accept(packets, idr);
            } catch (RuntimeException ignored) {
                // one broken client must never stop the stream
            }
        }
    }

    /** Waits until the encoder produced SPS+PPS (needed for the SDP of RTSP sessions). */
    public boolean awaitParameterSets(long timeoutMs) {
        try {
            return parametersKnown.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The "a=fmtp" value for the SDP. sprop-parameter-sets is included once SPS/PPS are known. */
    public String fmtp() {
        StringBuilder sb = new StringBuilder("packetization-mode=1");
        byte[] s = sps;
        byte[] p = pps;
        if (s != null && s.length >= 4) {
            sb.append(String.format(";profile-level-id=%02X%02X%02X", s[1] & 0xFF, s[2] & 0xFF, s[3] & 0xFF));
            if (p != null) {
                Base64.Encoder enc = Base64.getEncoder();
                sb.append(";sprop-parameter-sets=").append(enc.encodeToString(s)).append(',').append(enc.encodeToString(p));
            }
        }
        return sb.toString();
    }

    public void closeAllOutputs() {
        for (RtpOutput o : outputs) {
            try {
                o.close();
            } catch (RuntimeException ignored) {
            }
        }
        outputs.clear();
    }
}
