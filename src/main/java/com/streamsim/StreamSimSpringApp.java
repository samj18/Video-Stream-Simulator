package com.streamsim;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Spring Boot context (services + config). The UI is JavaFX, see {@link FxApp}. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StreamSimSpringApp {
}
