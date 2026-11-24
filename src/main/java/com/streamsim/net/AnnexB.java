package com.streamsim.net;

import java.util.ArrayList;
import java.util.List;

/** Helpers for H.264 Annex-B byte streams (start-code delimited NAL units). */
public final class AnnexB {

    public static final int NAL_SLICE_IDR = 5;
    public static final int NAL_SEI = 6;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;
    public static final int NAL_AUD = 9;
    public static final int NAL_FILLER = 12;

    private AnnexB() {
    }

    public static int nalType(byte[] nal) {
        return nal[0] & 0x1F;
    }

    /**
     * Splits an Annex-B buffer into NAL units (start codes removed).
     * Handles both 3-byte (00 00 01) and 4-byte (00 00 00 01) start codes.
     */
    public static List<byte[]> split(byte[] d, int off, int len) {
        List<byte[]> out = new ArrayList<>();
        int end = off + len;
        int i = off;
        int nalStart = -1;
        while (i + 2 < end) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                if (nalStart >= 0) {
                    addNal(out, d, nalStart, i);
                }
                nalStart = i + 3;
                i += 3;
            } else {
                i++;
            }
        }
        if (nalStart >= 0 && nalStart < end) {
            addNal(out, d, nalStart, end);
        }
        return out;
    }

    private static void addNal(List<byte[]> out, byte[] d, int start, int endExclusive) {
        int e = endExclusive;
        // trailing zero bytes belong to the next start code (a NAL can never end with 0x00)
        while (e > start && d[e - 1] == 0) {
            e--;
        }
        if (e > start) {
            byte[] nal = new byte[e - start];
            System.arraycopy(d, start, nal, 0, nal.length);
            out.add(nal);
        }
    }
}
