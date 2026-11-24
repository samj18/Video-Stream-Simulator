package com.streamsim;

import com.streamsim.ui.MainView;
import javafx.application.Application;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** JavaFX application: boots Spring in init() (background thread), builds the UI in start() (FX thread). */
public class FxApp extends Application {

    private ConfigurableApplicationContext context;

    @Override
    public void init() {
        context = new SpringApplicationBuilder(StreamSimSpringApp.class)
                .headless(false)
                .run(getParameters().getRaw().toArray(new String[0]));
    }

    @Override
    public void start(Stage stage) {
        context.getBean(MainView.class).show(stage);
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();     // triggers @PreDestroy on the services -> stops streams
        }
    }
}
