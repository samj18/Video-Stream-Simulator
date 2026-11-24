package com.streamsim.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves a file with HTTP Range support (RFC 9110 section 14): 200 full, 206 partial, 416 unsatisfiable,
 * HEAD, If-Range, Accept-Ranges. This is what makes seeking work in VLC, browsers' &lt;video&gt;, ffplay ...
 */
final class RangeFileResponder {

    private static final int CHUNK = 64 * 1024;
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final AtomicLong bytesServed;

    RangeFileResponder(AtomicLong bytesServed) {
        this.bytesServed = bytesServed;
    }

    void serve(HttpExchange ex, Path file) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD, OPTIONS");
            ex.sendResponseHeaders(405, -1);
            return;
        }
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(modified) + "\"";
        String lastModified = HTTP_DATE.format(Instant.ofEpochMilli(modified));

        var out = ex.getResponseHeaders();
        out.set("Accept-Ranges", "bytes");
        out.set("Content-Type", contentType(file));
        out.set("ETag", etag);
        out.set("Last-Modified", lastModified);
        out.set("Cache-Control", "no-cache");

        // Range is only honoured if If-Range (when present) still matches the file
        String rangeHeader = ex.getRequestHeaders().getFirst("Range");
        String ifRange = ex.getRequestHeaders().getFirst("If-Range");
        if (rangeHeader != null && ifRange != null && !ifRange.equals(etag) && !ifRange.equals(lastModified)) {
            rangeHeader = null;
        }

        long start = 0;
        long end = size - 1;
        int status = 200;
        if (rangeHeader != null) {
            long[] r = parseRange(rangeHeader, size);
            if (r != null && r[0] < 0) {                                  // unsatisfiable
                out.set("Content-Range", "bytes */" + size);
                ex.sendResponseHeaders(416, -1);
                return;
            }
            if (r != null) {
                start = r[0];
                end = r[1];
                status = 206;
                out.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
        }
        long length = size == 0 ? 0 : end - start + 1;

        if (method.equals("HEAD")) {
            out.set("Content-Length", Long.toString(length));
            ex.sendResponseHeaders(status, -1);
            return;
        }
        if (length == 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, length);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
             OutputStream os = ex.getResponseBody()) {
            ch.position(start);
            ByteBuffer buf = ByteBuffer.allocate(CHUNK);
            long remaining = length;
            while (remaining > 0) {
                buf.clear();
                buf.limit((int) Math.min(CHUNK, remaining));
                int n = ch.read(buf);
                if (n < 0) {
                    break;
                }
                os.write(buf.array(), 0, n);
                remaining -= n;
                bytesServed.addAndGet(n);
            }
        } catch (IOException clientGone) {
            // player seeked / closed the connection: normal for video playback
        }
    }

    /**
     * @return null = ignore the header and send the whole file; {-1,-1} = unsatisfiable (416);
     * otherwise {firstByte, lastByte} inclusive
     */
    static long[] parseRange(String header, long size) {
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }
        String spec = h.substring(6).trim();
        if (spec.contains(",")) {
            return null;                                  // multi-range: allowed to answer with the full file
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            if (a.isEmpty()) {                            // suffix range: last N bytes
                long n = Long.parseLong(b);
                if (n <= 0 || size == 0) {
                    return new long[]{-1, -1};
                }
                return new long[]{Math.max(0, size - n), size - 1};
            }
            long first = Long.parseLong(a);
            long last = b.isEmpty() ? Long.MAX_VALUE : Long.parseLong(b);
            if (first < 0 || last < first) {
                return null;                              // syntactically invalid: ignore
            }
            if (first >= size) {
                return new long[]{-1, -1};
            }
            return new long[]{first, Math.min(last, size - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String contentType(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".ts")) return "video/mp2t";
        if (n.endsWith(".flv")) return "video/x-flv";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }
}
