package com.streamsim.net;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 6184 H.264 RTP packetizer (packetization-mode=1): single NAL unit packets and FU-A fragmentation.
 */
public final class H264RtpPacketizer {

    public static final int PAYLOAD_TYPE = 96;
    public static final int CLOCK_RATE = 90_000;

    private final int maxPayload;
    private final int ssrc;
    private int seq;

    public H264RtpPacketizer(int maxPayload) {
        this.maxPayload = maxPayload;
        SecureRandom rnd = new SecureRandom();
        this.ssrc = rnd.nextInt();
        this.seq = rnd.nextInt(0xFFFF);
    }

    /** Packetizes the NAL units of ONE access unit (one video frame). All packets share the timestamp. */
    public synchronized List<byte[]> packetize(List<byte[]> nals, long timestamp90k) {
        List<byte[]> packets = new ArrayList<>();
        for (byte[] nal : nals) {
            if (nal.length <= maxPayload) {
                byte[] p = newPacket(nal.length, timestamp90k);
                System.arraycopy(nal, 0, p, 12, nal.length);
                packets.add(p);
            } else {
                int nalHeader = nal[0] & 0xFF;
                int fuIndicator = (nalHeader & 0xE0) | 28;      // FU-A
                int nalType = nalHeader & 0x1F;
                int pos = 1;                                    // NAL header byte is carried in FU header
                int chunk = maxPayload - 2;
                while (pos < nal.length) {
                    int n = Math.min(chunk, nal.length - pos);
                    boolean first = pos == 1;
                    boolean last = pos + n >= nal.length;
                    byte[] p = newPacket(2 + n, timestamp90k);
                    p[12] = (byte) fuIndicator;
                    p[13] = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0) | nalType);
                    System.arraycopy(nal, pos, p, 14, n);
                    packets.add(p);
                    pos += n;
                }
            }
        }
        if (!packets.isEmpty()) {
            byte[] last = packets.get(packets.size() - 1);
            last[1] = (byte) (last[1] | 0x80);                  // marker bit = end of access unit
        }
        return packets;
    }

    private byte[] newPacket(int payloadLen, long ts) {
        byte[] p = new byte[12 + payloadLen];
        p[0] = (byte) 0x80;                                     // V=2
        p[1] = (byte) PAYLOAD_TYPE;
        p[2] = (byte) (seq >> 8);
        p[3] = (byte) seq;
        seq = (seq + 1) & 0xFFFF;
        p[4] = (byte) (ts >> 24);
        p[5] = (byte) (ts >> 16);
        p[6] = (byte) (ts >> 8);
        p[7] = (byte) ts;
        p[8] = (byte) (ssrc >> 24);
        p[9] = (byte) (ssrc >> 16);
        p[10] = (byte) (ssrc >> 8);
        p[11] = (byte) ssrc;
        return p;
    }
}
