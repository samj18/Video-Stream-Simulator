package com.streamsim.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.List;

/** Pushes RTP packets to a fixed ip:port over UDP (unicast, broadcast or multicast). */
public final class UdpRtpOutput implements RtpOutput {

    private final MulticastSocket socket;
    private final InetAddress dest;
    private final int port;

    public UdpRtpOutput(InetAddress dest, int port) throws IOException {
        this.dest = dest;
        this.port = port;
        this.socket = new MulticastSocket();
        this.socket.setTimeToLive(16);
        this.socket.setSendBufferSize(1 << 20);
        if (dest.getHostAddress().endsWith(".255")) {
            this.socket.setBroadcast(true);
        }
    }

    public boolean isMulticast() {
        return dest.isMulticastAddress();
    }

    @Override
    public void accept(List<byte[]> packets, boolean keyFrame) {
        try {
            for (byte[] p : packets) {
                socket.send(new DatagramPacket(p, p.length, dest, port));
            }
        } catch (IOException e) {
            // destination unreachable / ICMP errors: keep streaming, receiver may start later
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}
