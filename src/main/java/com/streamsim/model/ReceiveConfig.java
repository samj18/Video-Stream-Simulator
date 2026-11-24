package com.streamsim.model;

/**
 * @param url           RTSP url, or generic url / sdp path
 * @param listenAddress RTP: local address (0.0.0.0 or a multicast group such as 239.255.0.1)
 * @param port          RTP: local UDP port
 * @param rtspTcp       RTSP: use TCP interleaved transport instead of UDP
 * @param autoReconnect retry when the stream is not available yet / drops
 */
public record ReceiveConfig(
        ReceiveProtocol protocol,
        String url,
        String listenAddress,
        int port,
        boolean rtspTcp,
        boolean autoReconnect) {
}
