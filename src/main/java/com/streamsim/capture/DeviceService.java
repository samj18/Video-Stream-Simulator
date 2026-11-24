package com.streamsim.capture;

import org.bytedeco.javacpp.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds webcams / screens (using the FFmpeg binary bundled with JavaCV) and local IP addresses. */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern MAC_DEVICE = Pattern.compile("\\]\\s*\\[(\\d+)]\\s*(.+)$");

    public enum Os { WINDOWS, LINUX, MAC }

    public static Os os() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (n.contains("win")) {
            return Os.WINDOWS;
        }
        if (n.contains("mac") || n.contains("darwin")) {
            return Os.MAC;
        }
        return Os.LINUX;
    }

    /** Webcam identifiers as the user should see / the factory expects them. */
    public List<String> listWebcams() {
        try {
            switch (os()) {
                case WINDOWS:
                    return parseDshow(runFfmpeg("-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"));
                case MAC:
                    return parseAvFoundation(runFfmpeg("-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""), false);
                default:
                    return listV4l();
            }
        } catch (Exception e) {
            log.warn("Could not list webcams: {}", e.toString());
            return Collections.emptyList();
        }
    }

    /** Desktop capture inputs (macOS lists real screens; Windows/Linux have a single default). */
    public List<String> listDesktopInputs() {
        if (os() == Os.MAC) {
            try {
                List<String> screens = parseAvFoundation(
                        runFfmpeg("-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""), true);
                if (!screens.isEmpty()) {
                    return screens;
                }
            } catch (Exception e) {
                log.warn("Could not list screens: {}", e.toString());
            }
        }
        return List.of(defaultDesktopInput());
    }

    public String defaultDesktopInput() {
        switch (os()) {
            case WINDOWS:
                return "desktop";
            case MAC:
                return "1 - Capture screen 0";
            default:
                String d = System.getenv("DISPLAY");
                return d == null || d.isBlank() ? ":0.0" : d;
        }
    }

    /** 0.0.0.0 first, then every IPv4 address of this PC. */
    public List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not enumerate network interfaces", e);
        }
        out.remove("127.0.0.1");
        out.add(0, "0.0.0.0");
        out.add("127.0.0.1");
        return out;
    }

    /** The address to show in URLs when the server is bound to 0.0.0.0. */
    public String primaryAddress() {
        for (String a : localAddresses()) {
            if (!a.equals("0.0.0.0") && !a.equals("127.0.0.1")) {
                return a;
            }
        }
        return "127.0.0.1";
    }

    // ---------------------------------------------------------------------------------------------

    private String runFfmpeg(String... args) throws IOException, InterruptedException {
        String ffmpeg = Loader.load(org.bytedeco.ffmpeg.ffmpeg.class);
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        p.waitFor(10, TimeUnit.SECONDS);
        p.destroyForcibly();
        return sb.toString();
    }

    static List<String> parseDshow(String output) {
        List<String> devices = new ArrayList<>();
        boolean inVideoSection = false;
        for (String line : output.split("\\R")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("directshow video devices")) {
                inVideoSection = true;
                continue;
            }
            if (l.contains("directshow audio devices")) {
                inVideoSection = false;
                continue;
            }
            if (l.contains("alternative name")) {
                continue;
            }
            Matcher m = QUOTED.matcher(line);
            if (!m.find()) {
                continue;
            }
            boolean taggedVideo = l.contains("(video)");
            boolean taggedOther = l.contains("(audio)") || l.contains("(none)");
            if (taggedVideo || (inVideoSection && !taggedOther)) {
                if (!devices.contains(m.group(1))) {
                    devices.add(m.group(1));
                }
            }
        }
        return devices;
    }

    static List<String> parseAvFoundation(String output, boolean screens) {
        List<String> devices = new ArrayList<>();
        boolean inVideo = false;
        for (String line : output.split("\\R")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("avfoundation video devices")) {
                inVideo = true;
                continue;
            }
            if (l.contains("avfoundation audio devices")) {
                inVideo = false;
                continue;
            }
            if (!inVideo) {
                continue;
            }
            Matcher m = MAC_DEVICE.matcher(line);
            if (m.find()) {
                boolean isScreen = m.group(2).toLowerCase(Locale.ROOT).startsWith("capture screen");
                if (isScreen == screens) {
                    devices.add(m.group(1) + " - " + m.group(2).trim());
                }
            }
        }
        return devices;
    }

    private List<String> listV4l() {
        List<String> devices = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of("/dev"), "video*")) {
            for (Path p : ds) {
                devices.add(p.toString());
            }
        } catch (IOException e) {
            log.debug("No /dev/video* devices: {}", e.toString());
        }
        Collections.sort(devices);
        return devices;
    }
}
