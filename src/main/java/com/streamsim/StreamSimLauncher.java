package com.streamsim;

import javafx.application.Application;

/**
 * Plain main class (does NOT extend Application) so the app also starts from a fat jar / jpackage image.
 */
public class StreamSimLauncher {
    public static void main(String[] args) {
        Application.launch(FxApp.class, args);
    }
}
