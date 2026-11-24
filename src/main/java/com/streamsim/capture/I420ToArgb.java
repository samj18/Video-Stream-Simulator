package com.streamsim.capture;

import java.nio.ByteBuffer;

/** Planar I420 (BT.601 limited range) -> ARGB pixels for the JavaFX preview, optionally subsampled. Pure Java. */
public final class I420ToArgb {

    private I420ToArgb() {
    }

    /**
     * @param y,u,v    plane buffers (absolute reads only, position is ignored)
     * @param maxWidth subsample so the output is at most this wide (0 = full size)
     */
    public static FramePixels convert(ByteBuffer y, int strideY, ByteBuffer u, int strideU,
                                      ByteBuffer v, int strideV, int width, int height, int maxWidth) {
        int step = maxWidth > 0 ? Math.max(1, width / maxWidth) : 1;
        int ow = width / step;
        int oh = height / step;
        int[] out = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            int sy = oy * step;
            int yRow = sy * strideY;
            int uRow = (sy >> 1) * strideU;
            int vRow = (sy >> 1) * strideV;
            int o = oy * ow;
            for (int ox = 0; ox < ow; ox++) {
                int sx = ox * step;
                int yy = ((y.get(yRow + sx) & 0xFF) - 16) * 298;
                int uu = (u.get(uRow + (sx >> 1)) & 0xFF) - 128;
                int vv = (v.get(vRow + (sx >> 1)) & 0xFF) - 128;
                int r = clamp((yy + 409 * vv + 128) >> 8);
                int g = clamp((yy - 100 * uu - 208 * vv + 128) >> 8);
                int b = clamp((yy + 516 * uu + 128) >> 8);
                out[o + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return new FramePixels(out, ow, oh);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
