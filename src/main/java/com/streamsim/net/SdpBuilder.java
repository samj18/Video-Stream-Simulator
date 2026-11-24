package com.streamsim.net;

/** Builds SDP documents for the H.264 stream. */
public final class SdpBuilder {

    private SdpBuilder() {
    }

    /** SDP returned by the RTSP server in the DESCRIBE response. */
    public static String forRtsp(String fmtp, String serverAddress) {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 " + serverAddress + "\r\n"
                + "s=Video Stream Simulator\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "t=0 0\r\n"
                + "a=control:*\r\n"
                + "a=range:npt=0-\r\n"
                + "m=video 0 RTP/AVP " + H264RtpPacketizer.PAYLOAD_TYPE + "\r\n"
                + "a=rtpmap:" + H264RtpPacketizer.PAYLOAD_TYPE + " H264/" + H264RtpPacketizer.CLOCK_RATE + "\r\n"
                + "a=fmtp:" + H264RtpPacketizer.PAYLOAD_TYPE + " " + fmtp + "\r\n"
                + "a=control:trackID=0\r\n";
    }

    /**
     * SDP file for plain RTP push mode. Open this file in VLC (or ffplay) on the receiving PC.
     *
     * @param destination ip the packets are sent to (unicast / multicast)
     */
    public static String forRtpPush(String destination, int port, boolean multicast, String fmtp) {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 " + destination + "\r\n"
                + "s=Video Stream Simulator\r\n"
                + "c=IN IP4 " + destination + (multicast ? "/16" : "") + "\r\n"
                + "t=0 0\r\n"
                + "m=video " + port + " RTP/AVP " + H264RtpPacketizer.PAYLOAD_TYPE + "\r\n"
                + "a=rtpmap:" + H264RtpPacketizer.PAYLOAD_TYPE + " H264/" + H264RtpPacketizer.CLOCK_RATE + "\r\n"
                + "a=fmtp:" + H264RtpPacketizer.PAYLOAD_TYPE + " " + fmtp + "\r\n";
    }

    /** SDP used by our own receiver to listen for RTP on a local port (the sender is not known). */
    public static String forRtpReceive(String listenAddress, int port) {
        boolean multicast = isMulticast(listenAddress);
        String c = multicast ? listenAddress + "/16" : "0.0.0.0";
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                + "s=Video Stream Simulator receiver\r\n"
                + "c=IN IP4 " + c + "\r\n"
                + "t=0 0\r\n"
                + "m=video " + port + " RTP/AVP " + H264RtpPacketizer.PAYLOAD_TYPE + "\r\n"
                + "a=rtpmap:" + H264RtpPacketizer.PAYLOAD_TYPE + " H264/" + H264RtpPacketizer.CLOCK_RATE + "\r\n";
    }

    public static boolean isMulticast(String ip) {
        try {
            int first = Integer.parseInt(ip.trim().split("\\.")[0]);
            return first >= 224 && first <= 239;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
