package com.streamsim.model;

public enum ReceiveProtocol {
    RTSP("RTSP (connect to rtsp://ip:port/path)"),
    RTP("RTP (listen on a local port)"),
    HTTP("HTTP / file URL (Range requests - seekable)"),
    WEBRTC("WebRTC (WHEP URL, e.g. http://ip:8080/whep)"),
    URL("Any URL / SDP file (udp://, http://, file.sdp ...)");

    private final String label;

    ReceiveProtocol(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
