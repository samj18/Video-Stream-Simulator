package com.streamsim.model;

public enum OutputProtocol {
    RTSP("RTSP server (clients connect to me)"),
    RTP("RTP push (I send to IP:port)"),
    WEBRTC("WebRTC (browser viewer, WHEP)"),
    HTTP("HTTP file server (Range requests)");

    private final String label;

    OutputProtocol(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
