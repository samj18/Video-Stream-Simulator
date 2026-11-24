package com.streamsim.model;

public enum SourceType {
    WEBCAM("Webcam"),
    DESKTOP("Desktop (screen capture)"),
    FILE("Video file / URL");

    private final String label;

    SourceType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
