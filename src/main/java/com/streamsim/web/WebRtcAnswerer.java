package com.streamsim.web;

/** Bridge between the HTTP signalling endpoint (WHEP) and whatever WebRTC stack produces the answer. */
public interface WebRtcAnswerer {

    /**
     * Blocking: applies the viewer's SDP offer and returns the SDP answer (with all ICE candidates included,
     * because WHEP is a single HTTP round trip).
     */
    String answer(String offerSdp) throws Exception;

    int viewerCount();
}
