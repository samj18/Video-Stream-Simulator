package com.streamsim.net;

import java.util.List;

/** A consumer of RTP packets (an RTSP client, or a plain UDP destination). */
public interface RtpOutput {

    /**
     * @param packets  all RTP packets of one access unit (video frame)
     * @param keyFrame true if the access unit is an IDR frame (a client may start playing here)
     */
    void accept(List<byte[]> packets, boolean keyFrame);

    void close();
}
