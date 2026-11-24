package com.streamsim.ui;

import com.streamsim.capture.DeviceService;
import com.streamsim.capture.FramePixels;
import com.streamsim.config.StreamSimProperties;
import com.streamsim.model.OutputProtocol;
import com.streamsim.model.PublishConfig;
import com.streamsim.model.ReceiveConfig;
import com.streamsim.model.ReceiveProtocol;
import com.streamsim.model.SourceType;
import com.streamsim.service.StreamPublisherService;
import com.streamsim.service.StreamReceiverService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whole JavaFX UI: a Publisher tab and a Receiver tab.
 * {@code @Lazy} so the controls are created on the FX thread (first getBean in FxApp.start), not during Spring startup.
 */
@Lazy
@Component
public class MainView {

    private static final String SAME_AS_SOURCE = "Same as source";
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*[xX]\\s*(\\d+)");
    private static final String CSS = MainView.class.getResource("/app.css").toExternalForm();

    private final StreamPublisherService publisher;
    private final StreamReceiverService receiver;
    private final DeviceService devices;
    private final StreamSimProperties props;

    // ---- publisher controls
    private final ComboBox<SourceType> sourceBox = new ComboBox<>(FXCollections.observableArrayList(SourceType.values()));
    private final ComboBox<String> deviceBox = new ComboBox<>();
    private final Button refreshBtn = new Button("Refresh");
    private final TextField captureSizeField = new TextField();
    private final TextField fileField = new TextField();
    private final Button browseBtn = new Button("Browse...");
    private final CheckBox loopBox = new CheckBox("Loop");
    private final ComboBox<OutputProtocol> protoBox = new ComboBox<>(FXCollections.observableArrayList(OutputProtocol.values()));
    private final Label hostLabel = new Label("Bind IP:");
    private final ComboBox<String> hostBox = new ComboBox<>();
    private final Spinner<Integer> portSpin = new Spinner<>();
    private final TextField pathField = new TextField();
    private final ComboBox<String> resBox = new ComboBox<>();
    private final Spinner<Integer> fpsSpin = new Spinner<>();
    private final Spinner<Integer> bitrateSpin = new Spinner<>();
    private final Button startBtn = new Button("Start streaming");
    private final Button stopBtn = new Button("Stop");
    private final TextField urlField = new TextField();
    private final Button copyUrlBtn = new Button("Copy");
    private final TextArea sdpArea = new TextArea();
    private final Button saveSdpBtn = new Button("Save SDP file...");
    private final Label pubStatus = new Label("Idle");
    private final Label pubStats = new Label("");
    private final VideoPane pubPreview = new VideoPane("Preview of the outgoing video");

    // ---- receiver controls
    private final ComboBox<ReceiveProtocol> rProtoBox = new ComboBox<>(FXCollections.observableArrayList(ReceiveProtocol.values()));
    private final TextField rtspUrlField = new TextField("rtsp://127.0.0.1:8554/live");
    private final CheckBox tcpBox = new CheckBox("Use TCP transport (more reliable through firewalls / Wi-Fi)");
    private final ComboBox<String> listenBox = new ComboBox<>();
    private final Spinner<Integer> listenPortSpin = new Spinner<>();
    private final TextField genericUrlField = new TextField();
    private final TextField httpUrlField = new TextField("http://127.0.0.1:8080/video");
    private final TextField whepUrlField = new TextField("http://127.0.0.1:8080/whep");
    private final CheckBox reconnectBox = new CheckBox("Auto-reconnect");
    // player bar (only visible for seekable streams, i.e. a file served over HTTP)
    private final Button playPauseBtn = new Button("Pause");
    private final Button backBtn = new Button("-10 s");
    private final Button fwdBtn = new Button("+10 s");
    private final Slider seekSlider = new Slider(0, 1, 0);
    private final Label timeLabel = new Label("00:00 / 00:00");
    private boolean seekDragging;
    private final Button rStartBtn = new Button("Start receiving");
    private final Button rStopBtn = new Button("Stop");
    private final Label rStatus = new Label("Idle");
    private final VideoPane rView = new VideoPane("Received video appears here");

    private boolean publishing;
    private Node deviceRow;
    private Node fileRow;
    private Node sizeRow;
    private Node pathRow;
    private Node rtspRows;
    private Node rtpRows;
    private Node genericRows;
    private Node httpRows;
    private Node whepRows;
    private Node playerBar;

    public MainView(StreamPublisherService publisher, StreamReceiverService receiver,
                    DeviceService devices, StreamSimProperties props) {
        this.publisher = publisher;
        this.receiver = receiver;
        this.devices = devices;
        this.props = props;
    }

    public void show(Stage stage) {
        Tab pubTab = new Tab("Publisher (send)", buildPublisher());
        Tab recvTab = new Tab("Receiver (receive)", buildReceiver());
        pubTab.setClosable(false);
        recvTab.setClosable(false);
        TabPane tabs = new TabPane(pubTab, recvTab);

        Scene scene = new Scene(tabs, 1200, 760);
        scene.getStylesheets().add(CSS);
        stage.setTitle("Video Stream Simulator - RTSP / RTP");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            publisher.stop();
            receiver.stop();
        });
        stage.show();

        onSourceChanged();
        onProtocolChanged();
        onReceiveProtocolChanged();
    }

    // =============================================================================================
    //  Publisher tab
    // =============================================================================================

    private Node buildPublisher() {
        sourceBox.setValue(SourceType.WEBCAM);
        sourceBox.setOnAction(e -> onSourceChanged());

        deviceBox.setEditable(true);
        deviceBox.setPrefWidth(300);
        refreshBtn.setOnAction(e -> loadDevices());
        captureSizeField.setPromptText("optional, e.g. 1280x720");
        fileField.setPromptText("C:\\videos\\demo.mp4  (or rtsp://... / http://...)");
        fileField.setPrefWidth(300);
        browseBtn.setOnAction(e -> browseFile());
        loopBox.setSelected(true);

        protoBox.setValue(OutputProtocol.RTSP);
        protoBox.setOnAction(e -> onProtocolChanged());
        hostBox.setEditable(true);
        hostBox.setPrefWidth(200);
        portSpin.setEditable(true);
        portSpin.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, props.rtspPort()));
        portSpin.setPrefWidth(110);
        pathField.setText(props.rtspPath());

        resBox.setEditable(true);
        resBox.setItems(FXCollections.observableArrayList(SAME_AS_SOURCE, "1920x1080", "1280x720", "854x480", "640x360", "320x240"));
        resBox.setValue("1280x720");
        fpsSpin.setEditable(true);
        fpsSpin.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, props.fps()));
        fpsSpin.setPrefWidth(90);
        bitrateSpin.setEditable(true);
        bitrateSpin.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(100, 50_000, props.bitrateKbps(), 100));
        bitrateSpin.setPrefWidth(110);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        int r = 0;
        pubStatus.getStyleClass().add("status-label");
        pubStats.getStyleClass().add("status-label");
        rStatus.getStyleClass().add("status-label");
        form.add(section("Video source"), 0, r++, 2, 1);
        form.add(new Label("Source:"), 0, r);
        form.add(sourceBox, 1, r++);

        deviceRow = new HBox(8, deviceBox, refreshBtn);
        form.add(new Label("Device:"), 0, r);
        form.add(deviceRow, 1, r++);
        sizeRow = captureSizeField;
        form.add(new Label("Capture size:"), 0, r);
        form.add(sizeRow, 1, r++);
        fileRow = new HBox(8, fileField, browseBtn, loopBox);
        form.add(new Label("File / URL:"), 0, r);
        form.add(fileRow, 1, r++);

        form.add(section("Publish as"), 0, r++, 2, 1);
        form.add(new Label("Protocol:"), 0, r);
        form.add(protoBox, 1, r++);
        form.add(hostLabel, 0, r);
        form.add(hostBox, 1, r++);
        form.add(new Label("Port:"), 0, r);
        form.add(portSpin, 1, r++);
        pathRow = pathField;
        form.add(new Label("RTSP path:"), 0, r);
        form.add(pathRow, 1, r++);

        form.add(section("Encoding (H.264)"), 0, r++, 2, 1);
        form.add(new Label("Resolution:"), 0, r);
        form.add(resBox, 1, r++);
        form.add(new Label("FPS:"), 0, r);
        form.add(fpsSpin, 1, r++);
        form.add(new Label("Bitrate (kbps):"), 0, r);
        form.add(bitrateSpin, 1, r++);

        startBtn.setDefaultButton(true);
        startBtn.setOnAction(e -> startPublishing());
        stopBtn.setOnAction(e -> publisher.stop());
        stopBtn.setDisable(true);
        HBox buttons = new HBox(10, startBtn, stopBtn);
        form.add(buttons, 0, r++, 2, 1);

        // right side: preview + connect info
        urlField.setEditable(false);
        urlField.setPromptText("Connection URL appears here after Start");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        copyUrlBtn.setOnAction(e -> copy(urlField.getText()));
        sdpArea.setEditable(false);
        sdpArea.setPrefRowCount(8);
        sdpArea.setStyle("-fx-font-family: monospace;");
        sdpArea.setPromptText("RTP push mode: the SDP appears here. Save it as stream.sdp and open it with VLC.");
        saveSdpBtn.setOnAction(e -> saveSdp());

        VBox right = new VBox(10,
                pubPreview,
                new HBox(8, new Label("Stream URL:"), urlField, copyUrlBtn),
                new HBox(8, new Label("SDP (for VLC in RTP mode):"), saveSdpBtn),
                sdpArea,
                new Separator(Orientation.HORIZONTAL),
                pubStatus,
                pubStats);
        VBox.setVgrow(pubPreview, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setLeft(form);
        root.setCenter(right);
        BorderPane.setMargin(right, new Insets(0, 0, 0, 16));
        return root;
    }

    private void onSourceChanged() {
        SourceType s = sourceBox.getValue();
        setVisible(deviceRow, s != SourceType.FILE);
        setVisible(sizeRow, s != SourceType.FILE);
        setVisible(fileRow, s == SourceType.FILE);
        refreshBtn.setVisible(s != SourceType.FILE);
        if (s != SourceType.FILE) {
            loadDevices();
        }
    }

    private void loadDevices() {
        SourceType s = sourceBox.getValue();
        deviceBox.setPromptText(s == SourceType.WEBCAM ? "searching webcams..." : "");
        Thread t = new Thread(() -> {
            List<String> items = s == SourceType.WEBCAM ? devices.listWebcams() : devices.listDesktopInputs();
            Platform.runLater(() -> {
                deviceBox.setItems(FXCollections.observableArrayList(items));
                if (!items.isEmpty()) {
                    deviceBox.setValue(items.get(0));
                } else {
                    deviceBox.setValue("");
                    deviceBox.setPromptText(s == SourceType.WEBCAM
                            ? "no webcam found - type the device name" : "");
                }
            });
        }, "device-scan");
        t.setDaemon(true);
        t.start();
    }

    private void onProtocolChanged() {
        OutputProtocol p = protoBox.getValue();
        switch (p) {
            case RTSP -> {
                hostLabel.setText("Bind IP:");
                hostBox.setItems(FXCollections.observableArrayList(devices.localAddresses()));
                hostBox.setValue("0.0.0.0");
                portSpin.getValueFactory().setValue(props.rtspPort());
            }
            case RTP -> {
                hostLabel.setText("Destination IP:");
                hostBox.setItems(FXCollections.observableArrayList(props.rtpDestination(), "239.255.0.1"));
                hostBox.setValue(props.rtpDestination());
                portSpin.getValueFactory().setValue(props.rtpPort());
            }
            case WEBRTC, HTTP -> {
                hostLabel.setText("Bind IP:");
                hostBox.setItems(FXCollections.observableArrayList(devices.localAddresses()));
                hostBox.setValue("0.0.0.0");
                portSpin.getValueFactory().setValue(props.httpPort());
            }
        }
        if (p == OutputProtocol.HTTP && sourceBox.getValue() != SourceType.FILE) {
            sourceBox.setValue(SourceType.FILE);              // HTTP mode serves a file as-is
            onSourceChanged();
        }
        setVisible(pathRow, p == OutputProtocol.RTSP);
        saveSdpBtn.setVisible(p == OutputProtocol.RTP);
        sdpArea.setVisible(p == OutputProtocol.RTP);
        sdpArea.setManaged(p == OutputProtocol.RTP);
        refreshPublisherControls();
    }

    /** One place decides what is enabled, from: is a stream running + which protocol is selected. */
    private void refreshPublisherControls() {
        OutputProtocol p = protoBox.getValue();
        boolean http = p == OutputProtocol.HTTP;
        boolean webrtc = p == OutputProtocol.WEBRTC;
        for (Node n : new Node[]{deviceBox, refreshBtn, captureSizeField, fileField, browseBtn, loopBox,
                protoBox, hostBox, portSpin, pathField}) {
            n.setDisable(publishing);
        }
        sourceBox.setDisable(publishing || http);             // HTTP = file only
        resBox.setDisable(publishing || http);                // file is served untouched
        fpsSpin.setDisable(publishing || http);
        bitrateSpin.setDisable(publishing || http || webrtc); // libwebrtc adapts the bitrate per viewer
        startBtn.setDisable(publishing);
        stopBtn.setDisable(!publishing);
    }

    private void startPublishing() {
        PublishConfig cfg;
        try {
            cfg = readPublishConfig();
        } catch (IllegalArgumentException ex) {
            alert(ex.getMessage());
            return;
        }
        urlField.clear();
        sdpArea.clear();
        setPublishing(true);
        pubPreview.clear();
        try {
            publisher.start(cfg, new StreamPublisherService.Listener() {
                @Override
                public void onPreview(FramePixels frame) {
                    pubPreview.show(frame);
                }

                @Override
                public void onStatus(String text) {
                    Platform.runLater(() -> pubStatus.setText(text));
                }

                @Override
                public void onStats(StreamPublisherService.PublishStats s) {
                    Platform.runLater(() -> pubStats.setText(s.width() == 0
                            ? String.format(Locale.ROOT, "%s   %.0f kbps   %d connection(s)",
                                    s.encoder(), s.kbps(), s.clients())
                            : String.format(Locale.ROOT, "%s  %dx%d   %.1f fps   %.0f kbps   %d client(s)",
                                    s.encoder(), s.width(), s.height(), s.fps(), s.kbps(), s.clients())));
                }

                @Override
                public void onStreamInfo(String url, String sdp) {
                    Platform.runLater(() -> {
                        if (url != null) {
                            urlField.setText(url);
                        }
                        if (sdp != null) {
                            sdpArea.setText(sdp);
                            urlField.setText("(RTP) " + cfg.host() + ":" + cfg.port() + "  - open the SDP below with VLC");
                        }
                    });
                }

                @Override
                public void onStopped(String error) {
                    Platform.runLater(() -> {
                        setPublishing(false);
                        pubPreview.clear();
                        pubStats.setText("");
                        if (error != null) {
                            alert("Streaming stopped:\n" + error);
                        }
                    });
                }
            });
        } catch (RuntimeException ex) {
            setPublishing(false);
            alert(ex.getMessage());
        }
    }

    private PublishConfig readPublishConfig() {
        SourceType src = sourceBox.getValue();
        String file = fileField.getText() == null ? "" : fileField.getText().trim();
        if (src == SourceType.FILE && file.isEmpty()) {
            throw new IllegalArgumentException("Choose a video file or enter a URL.");
        }
        if (protoBox.getValue() == OutputProtocol.HTTP
                && (src != SourceType.FILE || file.matches("(?i)^[a-z][a-z0-9+.-]*://.*"))) {
            throw new IllegalArgumentException("HTTP mode serves a local video file: choose Source = Video file and browse to a file.");
        }
        String host = text(hostBox);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Enter an IP address.");
        }
        int w = 0;
        int h = 0;
        String res = text(resBox);
        if (!res.isEmpty() && !res.equalsIgnoreCase(SAME_AS_SOURCE)) {
            Matcher m = SIZE.matcher(res);
            if (!m.matches()) {
                throw new IllegalArgumentException("Resolution must look like 1280x720 or '" + SAME_AS_SOURCE + "'.");
            }
            w = Integer.parseInt(m.group(1));
            h = Integer.parseInt(m.group(2));
        }
        String cap = captureSizeField.getText() == null ? "" : captureSizeField.getText().trim();
        if (!cap.isEmpty() && !SIZE.matcher(cap).matches()) {
            throw new IllegalArgumentException("Capture size must look like 1280x720.");
        }
        return new PublishConfig(src, text(deviceBox), cap, file, loopBox.isSelected(),
                protoBox.getValue(), host, value(portSpin), pathField.getText().trim(), w, h,
                value(fpsSpin), value(bitrateSpin));
    }

    private void setPublishing(boolean running) {
        publishing = running;
        refreshPublisherControls();
    }

    private void browseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose a video file");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Video", "*.mp4", "*.mkv", "*.avi", "*.mov", "*.ts", "*.webm", "*.flv"));
        File f = fc.showOpenDialog(fileField.getScene().getWindow());
        if (f != null) {
            fileField.setText(f.getAbsolutePath());
        }
    }

    private void saveSdp() {
        if (sdpArea.getText().isBlank()) {
            alert("Start streaming first - the SDP is generated once the encoder produced the first frame.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("stream.sdp");
        File f = fc.showSaveDialog(sdpArea.getScene().getWindow());
        if (f != null) {
            try {
                Files.writeString(f.toPath(), sdpArea.getText(), StandardCharsets.US_ASCII);
            } catch (IOException e) {
                alert("Cannot save: " + e.getMessage());
            }
        }
    }

    // =============================================================================================
    //  Receiver tab
    // =============================================================================================

    private Node buildReceiver() {
        rProtoBox.setValue(ReceiveProtocol.RTSP);
        rProtoBox.setOnAction(e -> onReceiveProtocolChanged());
        rtspUrlField.setPrefWidth(380);
        listenBox.setEditable(true);
        listenBox.setItems(FXCollections.observableArrayList("0.0.0.0", "239.255.0.1"));
        listenBox.setValue("0.0.0.0");
        listenPortSpin.setEditable(true);
        listenPortSpin.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, props.rtpPort()));
        listenPortSpin.setPrefWidth(110);
        genericUrlField.setPromptText("http://ip:8080/video  |  udp://@:1234  |  C:\\path\\stream.sdp");
        genericUrlField.setPrefWidth(380);
        reconnectBox.setSelected(true);
        tcpBox.setSelected(false);

        rStartBtn.setDefaultButton(true);
        rStartBtn.setOnAction(e -> startReceiving());
        rStopBtn.setDisable(true);
        rStopBtn.setOnAction(e -> {
            receiver.stop();
            rStatus.setText("Stopping...");
        });

        GridPane rtsp = new GridPane();
        rtsp.setHgap(8);
        rtsp.setVgap(8);
        rtsp.add(new Label("RTSP URL:"), 0, 0);
        rtsp.add(rtspUrlField, 1, 0);
        rtsp.add(tcpBox, 1, 1);
        rtspRows = rtsp;

        GridPane rtp = new GridPane();
        rtp.setHgap(8);
        rtp.setVgap(8);
        rtp.add(new Label("Listen IP:"), 0, 0);
        rtp.add(listenBox, 1, 0);
        rtp.add(new Label("Listen port:"), 0, 1);
        rtp.add(listenPortSpin, 1, 1);
        rtp.add(new Label("H.264 over RTP, payload type 96 (what the Publisher and VLC send)."), 1, 2);
        rtpRows = rtp;

        GridPane generic = new GridPane();
        generic.setHgap(8);
        generic.setVgap(8);
        generic.add(new Label("URL / file:"), 0, 0);
        generic.add(genericUrlField, 1, 0);
        genericRows = generic;

        httpUrlField.setPrefWidth(380);
        whepUrlField.setPrefWidth(380);
        GridPane http = new GridPane();
        http.setHgap(8);
        http.setVgap(8);
        http.add(new Label("HTTP URL:"), 0, 0);
        http.add(httpUrlField, 1, 0);
        http.add(new Label("Seekable when the sender serves a file (Publisher > HTTP Range): use the Pause and +/-10 s controls below the video."), 1, 1);
        httpRows = http;

        GridPane whep = new GridPane();
        whep.setHgap(8);
        whep.setVgap(8);
        whep.add(new Label("WHEP URL:"), 0, 0);
        whep.add(whepUrlField, 1, 0);
        whep.add(new Label("Connects to a WebRTC sender that offers WHEP, e.g. this app's Publisher (WebRTC) at http://ip:8080/whep."), 1, 1);
        whepRows = whep;

        HBox top = new HBox(10, new Label("Protocol:"), rProtoBox, reconnectBox, rStartBtn, rStopBtn);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(10, top, rtspRows, rtpRows, httpRows, whepRows, genericRows,
                new Separator(Orientation.HORIZONTAL));

        buildPlayerBar();

        VBox.setVgrow(rView, Priority.ALWAYS);
        VBox root = new VBox(10, header, rView, playerBar, rStatus);
        root.setPadding(new Insets(12));
        return root;
    }

    private void buildPlayerBar() {
        HBox.setHgrow(seekSlider, Priority.ALWAYS);
        seekSlider.setMaxWidth(Double.MAX_VALUE);
        seekSlider.setOnMousePressed(e -> seekDragging = true);
        seekSlider.setOnMouseReleased(e -> {
            seekDragging = false;
            receiver.seekTo((long) seekSlider.getValue());
        });
        seekSlider.valueProperty().addListener((o, a, b) -> {
            if (seekDragging) {
                timeLabel.setText(formatTime(b.longValue()) + " / " + formatTime((long) seekSlider.getMax()));
            }
        });
        playPauseBtn.setOnAction(e -> {
            boolean pause = !receiver.isPaused();
            receiver.setPaused(pause);
            playPauseBtn.setText(pause ? "Play" : "Pause");
        });
        backBtn.setOnAction(e -> receiver.seekBy(-10_000));
        fwdBtn.setOnAction(e -> receiver.seekBy(10_000));
        timeLabel.setMinWidth(110);
        HBox bar = new HBox(8, playPauseBtn, backBtn, seekSlider, fwdBtn, timeLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        playerBar = bar;
        setVisible(playerBar, false);
    }

    /** Called on the FX thread for seekable streams (position in ms). */
    private void showPosition(long posMs, long durMs) {
        if (!playerBar.isVisible()) {
            setVisible(playerBar, true);
        }
        if (!seekDragging) {
            seekSlider.setMax(durMs);
            seekSlider.setValue(Math.min(posMs, durMs));
            timeLabel.setText(formatTime(posMs) + " / " + formatTime(durMs));
        }
    }

    private void resetPlayerBar() {
        setVisible(playerBar, false);
        seekDragging = false;
        seekSlider.setValue(0);
        playPauseBtn.setText("Pause");
        timeLabel.setText("00:00 / 00:00");
    }

    private static String formatTime(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format("%02d:%02d", s / 60, s % 60);
    }

    private void onReceiveProtocolChanged() {
        ReceiveProtocol p = rProtoBox.getValue();
        setVisible(httpRows, p == ReceiveProtocol.HTTP);
        setVisible(whepRows, p == ReceiveProtocol.WEBRTC);
        setVisible(rtspRows, p == ReceiveProtocol.RTSP);
        setVisible(rtpRows, p == ReceiveProtocol.RTP);
        setVisible(genericRows, p == ReceiveProtocol.URL);
    }

    private void startReceiving() {
        ReceiveProtocol p = rProtoBox.getValue();
        String url = switch (p) {
            case RTSP -> rtspUrlField.getText();
            case HTTP -> httpUrlField.getText();
            case WEBRTC -> whepUrlField.getText();
            default -> genericUrlField.getText();
        };
        if (p != ReceiveProtocol.RTP && (url == null || url.isBlank())) {
            alert("Enter the stream URL.");
            return;
        }
        String listen = text(listenBox);
        if (p == ReceiveProtocol.RTP && listen.isEmpty()) {
            alert("Enter the local IP to listen on (0.0.0.0 = any).");
            return;
        }
        ReceiveConfig cfg = new ReceiveConfig(p, url == null ? "" : url, listen, value(listenPortSpin),
                tcpBox.isSelected(), reconnectBox.isSelected());
        rStartBtn.setDisable(true);
        rStopBtn.setDisable(false);
        rProtoBox.setDisable(true);
        rView.clear();
        resetPlayerBar();
        try {
            receiver.start(cfg, new StreamReceiverService.Listener() {
                @Override
                public void onFrame(FramePixels frame) {
                    rView.show(frame);
                }

                @Override
                public void onStatus(String text) {
                    Platform.runLater(() -> rStatus.setText(text));
                }

                @Override
                public void onPosition(long positionUs, long durationUs) {
                    Platform.runLater(() -> showPosition(positionUs / 1000, durationUs / 1000));
                }

                @Override
                public void onStopped(String error) {
                    Platform.runLater(() -> {
                        resetPlayerBar();
                        rStartBtn.setDisable(false);
                        rStopBtn.setDisable(true);
                        rProtoBox.setDisable(false);
                        rView.clear();
                        if (error != null) {
                            alert("Receiving stopped:\n" + error);
                        }
                    });
                }
            });
        } catch (RuntimeException ex) {
            rStartBtn.setDisable(false);
            rStopBtn.setDisable(true);
            rProtoBox.setDisable(false);
            alert(ex.getMessage());
        }
    }

    // =============================================================================================
    //  helpers
    // =============================================================================================

    private static Label section(String title) {
        Label l = new Label(title);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static void setVisible(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
        // the row's label sits in column 0 of the same grid row: hide it too
        if (n.getParent() instanceof GridPane grid) {
            Integer row = GridPane.getRowIndex(n);
            for (Node sibling : grid.getChildren()) {
                Integer sr = GridPane.getRowIndex(sibling);
                if (sibling != n && sibling instanceof Label && (sr == null ? 0 : sr) == (row == null ? 0 : row)
                        && (GridPane.getColumnIndex(sibling) == null || GridPane.getColumnIndex(sibling) == 0)) {
                    sibling.setVisible(visible);
                    sibling.setManaged(visible);
                }
            }
        }
    }

    /** Reads an editable combo box (typed text included). */
    private static String text(ComboBox<String> box) {
        String t = box.isEditable() ? box.getEditor().getText() : box.getValue();
        return t == null ? "" : t.trim();
    }

    /** Commits typed text of an editable spinner and returns its value. */
    private static int value(Spinner<Integer> spinner) {
        if (spinner.isEditable()) {
            try {
                int typed = Integer.parseInt(spinner.getEditor().getText().trim());
                spinner.getValueFactory().setValue(typed);
            } catch (NumberFormatException ignored) {
                // keep the previous value
            }
        }
        return spinner.getValue();
    }

    private static void copy(String s) {
        if (s == null || s.isBlank()) {
            return;
        }
        ClipboardContent c = new ClipboardContent();
        c.putString(s);
        Clipboard.getSystemClipboard().setContent(c);
    }

    private static void alert(String message) {
        Alert a = new Alert(Alert.AlertType.WARNING, message);
        a.setHeaderText(null);
        a.getDialogPane().getStylesheets().add(CSS);
        a.show();
    }
}
