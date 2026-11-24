package com.streamsim.ui;

import com.streamsim.capture.FramePixels;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;

import java.util.concurrent.atomic.AtomicReference;

/** Black video surface that scales to its size; {@link #show} may be called from any thread. */
public class VideoPane extends StackPane {

    private final ImageView view = new ImageView();
    private final Label placeholder;
    private final AtomicReference<FramePixels> latest = new AtomicReference<>();
    private WritableImage image;

    public VideoPane(String placeholderText) {
        getStyleClass().add("video-pane");
        setMinSize(160, 90);
        setPrefSize(640, 360);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.fitWidthProperty().bind(widthProperty());
        view.fitHeightProperty().bind(heightProperty());
        placeholder = new Label(placeholderText);
        setAlignment(Pos.CENTER);
        getChildren().addAll(placeholder, view);
    }

    /** Thread-safe; frames that arrive faster than the FX thread can draw are dropped. */
    public void show(FramePixels frame) {
        if (latest.getAndSet(frame) == null) {          // no draw pending yet -> schedule one
            Platform.runLater(this::draw);
        }
    }

    public void clear() {
        latest.set(null);
        Platform.runLater(() -> {
            view.setImage(null);
            image = null;
            placeholder.setVisible(true);
        });
    }

    private void draw() {
        FramePixels f = latest.getAndSet(null);
        if (f == null) {
            return;
        }
        if (image == null || (int) image.getWidth() != f.width() || (int) image.getHeight() != f.height()) {
            image = new WritableImage(f.width(), f.height());
            view.setImage(image);
        }
        image.getPixelWriter().setPixels(0, 0, f.width(), f.height(),
                PixelFormat.getIntArgbInstance(), f.argb(), 0, f.width());
        placeholder.setVisible(false);
    }
}
