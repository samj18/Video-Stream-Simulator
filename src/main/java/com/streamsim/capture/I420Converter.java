package com.streamsim.capture;

import java.nio.ByteBuffer;

/**
 * BGR24 / BGRA / GRAY8 (as returned by FFmpegFrameGrabber) -> planar I420 (YUV 4:2:0), BT.601 limited range,
 * scaled with nearest neighbour to the requested size. Pure Java, no native code.
 */
public final class I420Converter {

    private I420Converter() {
    }

    /**
     * @param dstW,dstH output size, both must be even
     * @param y         output Y plane, dstW * dstH bytes
     * @param u,v       output chroma planes, (dstW/2) * (dstH/2) bytes each
     */
    public static void convert(ByteBuffer src, int srcW, int srcH, int srcStride, int channels,
                               int dstW, int dstH, byte[] y, byte[] u, byte[] v) {
        int[] xmap = new int[dstW];
        for (int x = 0; x < dstW; x++) {
            xmap[x] = Math.min(srcW - 1, (int) ((long) x * srcW / dstW)) * channels;
        }
        int cw = dstW / 2;
        for (int cy = 0; cy < dstH / 2; cy++) {
            int y0 = 2 * cy;
            int y1 = y0 + 1;
            int row0 = Math.min(srcH - 1, (int) ((long) y0 * srcH / dstH)) * srcStride;
            int row1 = Math.min(srcH - 1, (int) ((long) y1 * srcH / dstH)) * srcStride;
            for (int cx = 0; cx < cw; cx++) {
                int x0 = 2 * cx;
                int x1 = x0 + 1;
                int sumR = 0;
                int sumG = 0;
                int sumB = 0;
                for (int k = 0; k < 4; k++) {
                    int px = (k & 1) == 0 ? x0 : x1;
                    int py = (k & 2) == 0 ? y0 : y1;
                    int i = ((k & 2) == 0 ? row0 : row1) + xmap[px];
                    int r;
                    int g;
                    int b;
                    if (channels >= 3) {
                        b = src.get(i) & 0xFF;
                        g = src.get(i + 1) & 0xFF;
                        r = src.get(i + 2) & 0xFF;
                    } else {
                        r = src.get(i) & 0xFF;
                        g = r;
                        b = r;
                    }
                    y[py * dstW + px] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                    sumR += r;
                    sumG += g;
                    sumB += b;
                }
                int r = sumR >> 2;
                int g = sumG >> 2;
                int b = sumB >> 2;
                u[cy * cw + cx] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                v[cy * cw + cx] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
            }
        }
    }
}
