package com.streamsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Defaults shown in the UI, overridable in application.yml (prefix "streamsim"). */
@ConfigurationProperties(prefix = "streamsim")
public record StreamSimProperties(
        @DefaultValue("8554") int rtspPort,
        @DefaultValue("5004") int rtpPort,
        @DefaultValue("8080") int httpPort,
        @DefaultValue("live") String rtspPath,
        @DefaultValue("25") int fps,
        @DefaultValue("2000") int bitrateKbps,
        @DefaultValue("1200") int rtpMaxPayload,
        @DefaultValue("127.0.0.1") String rtpDestination) {
}
