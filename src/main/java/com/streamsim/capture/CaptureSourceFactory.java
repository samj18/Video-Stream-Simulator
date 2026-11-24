package com.streamsim.capture;

import com.streamsim.model.PublishConfig;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.awt.GraphicsEnvironment;
import java.awt.GraphicsDevice;
import java.awt.DisplayMode;

/** Builds the FFmpeg grabber for a webcam, the desktop or a file/URL. */
public final class CaptureSourceFactory {

    private CaptureSourceFactory() {
    }

    /**
     * @param withHints add framerate / size hints. Some devices reject unsupported values, so the caller
     *                  retries with {@code false} when the first start() fails.
     */
    public static FFmpegFrameGrabber create(PublishConfig c, boolean withHints) {
        DeviceService.Os os = DeviceService.os();
        String device = c.device() == null ? "" : c.device().trim();
        String size = c.captureSize() == null ? "" : c.captureSize().trim();
        FFmpegFrameGrabber g;

        switch (c.source()) {
            case WEBCAM -> {
                switch (os) {
                    case WINDOWS -> {
                        g = new FFmpegFrameGrabber("video=" + device);
                        g.setFormat("dshow");
                    }
                    case MAC -> {
                        g = new FFmpegFrameGrabber(macIndex(device));
                        g.setFormat("avfoundation");
                    }
                    default -> {
                        g = new FFmpegFrameGrabber(device.isEmpty() ? "/dev/video0" : device);
                        g.setFormat("v4l2");
                    }
                }
                if (withHints) {
                    g.setOption("framerate", String.valueOf(c.fps()));
                    if (!size.isEmpty()) {
                        g.setOption("video_size", size);
                    }
                }
                if (os == DeviceService.Os.WINDOWS) {
                    g.setOption("rtbufsize", "64M");
                }
            }
            case DESKTOP -> {
                switch (os) {
                    case WINDOWS -> {
                        g = new FFmpegFrameGrabber(device.isEmpty() ? "desktop" : device);
                        g.setFormat("gdigrab");
                        g.setOption("draw_mouse", "1");
                    }
                    case MAC -> {
                        g = new FFmpegFrameGrabber(macIndex(device));
                        g.setFormat("avfoundation");
                        g.setOption("capture_cursor", "1");
                    }
                    default -> {
                        g = new FFmpegFrameGrabber(device.isEmpty() ? ":0.0" : device);
                        g.setFormat("x11grab");
                        g.setOption("draw_mouse", "1");
                        // x11grab defaults to 640x480, so always pass the real screen size
                        g.setOption("video_size", size.isEmpty() ? screenSize() : size);
                    }
                }
                if (withHints) {
                    g.setOption("framerate", String.valueOf(c.fps()));
                }
                if (!size.isEmpty() && os != DeviceService.Os.LINUX) {
                    g.setOption("video_size", size);
                }
            }
            default -> {
                g = new FFmpegFrameGrabber(c.filePath() == null ? "" : c.filePath().trim());
            }
        }
        return g;
    }

    /** "1 - Capture screen 0" -> "1" (avfoundation wants the index; screens have no audio). */
    private static String macIndex(String device) {
        int dash = device.indexOf(" - ");
        String idx = dash > 0 ? device.substring(0, dash) : device;
        return idx.isBlank() ? "0" : idx.trim();
    }

    private static String screenSize() {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            DisplayMode dm = gd.getDisplayMode();
            return dm.getWidth() + "x" + dm.getHeight();
        } catch (Throwable t) {
            return "1920x1080";
        }
    }
}
