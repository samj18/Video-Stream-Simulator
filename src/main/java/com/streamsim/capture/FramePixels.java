package com.streamsim.capture;

import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;

/** A decoded video frame as ARGB ints, independent of JavaCV buffers (safe to hand to the FX thread). */
public record FramePixels(int[] argb, int width, int height) {

    /**
     * Converts a BGR24 / BGRA / GRAY8 frame (what FFmpegFrameGrabber returns) into ARGB.
     * Large frames are subsampled so the preview stays cheap (maxWidth = 0 -> no subsampling).
     */
    public static FramePixels from(Frame f, int maxWidth) {
        if (f == null || f.image == null || f.image.length == 0 || !(f.image[0] instanceof ByteBuffer bb)) {
            return null;
        }
        int w = f.imageWidth;
        int h = f.imageHeight;
        int ch = f.imageChannels;
        int stride = f.imageStride;
        int step = maxWidth > 0 ? Math.max(1, w / maxWidth) : 1;
        int ow = w / step;
        int oh = h / step;
        int[] out = new int[ow * oh];
        for (int y = 0; y < oh; y++) {
            int row = y * step * stride;
            int o = y * ow;
            for (int x = 0; x < ow; x++) {
                int i = row + x * step * ch;
                int r;
                int g;
                int b;
                if (ch >= 3) {
                    b = bb.get(i) & 0xFF;
                    g = bb.get(i + 1) & 0xFF;
                    r = bb.get(i + 2) & 0xFF;
                } else {
                    r = bb.get(i) & 0xFF;
                    g = r;
                    b = r;
                }
                out[o + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return new FramePixels(out, ow, oh);
    }
}
