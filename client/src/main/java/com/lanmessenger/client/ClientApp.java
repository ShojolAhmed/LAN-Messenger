package com.lanmessenger.client;

import com.lanmessenger.common.Protocol;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.Objects;

/**
 * Minimal JavaFX entry point for the LAN Messenger client.
 *
 * <p>For this first phase the client only shows a polished, dark-themed launch
 * screen to prove the UI toolkit and styling pipeline work. The chat interface
 * and networking are added in later phases.
 *
 * <p>The visual style lives entirely in {@code theme.css}; this class only
 * assigns style classes so that colours and spacing stay centralised.
 */
public class ClientApp extends Application {

    @Override
    public void start(Stage stage) {
        Label title = new Label(Protocol.APP_NAME);
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Secure messaging on your local network");
        subtitle.getStyleClass().add("app-subtitle");

        Label status = new Label("\u25CF  Ready");
        status.getStyleClass().add("status-pill");

        Label footer = new Label("v" + Protocol.APP_VERSION + "   \u2022   Phase 1 \u00B7 Project setup");
        footer.getStyleClass().add("app-footer");

        VBox card = new VBox(title, subtitle, status, footer);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);

        StackPane root = new StackPane(card);
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 960, 640);
        scene.getStylesheets().add(
                Objects.requireNonNull(
                        getClass().getResource("/com/lanmessenger/client/theme.css"),
                        "theme.css not found on classpath"
                ).toExternalForm()
        );

        stage.setTitle(Protocol.APP_NAME);
        stage.setScene(scene);
        stage.setMinWidth(720);
        stage.setMinHeight(480);
        stage.show();

        System.out.println(Protocol.APP_NAME + " client UI started");

        // Automated smoke test: when LANMSG_SMOKE=1 the window shows briefly and
        // then closes on its own, so verification runs do not block on the GUI.
        if ("1".equals(System.getenv("LANMSG_SMOKE"))) {
            PauseTransition delay = new PauseTransition(Duration.seconds(1.2));
            delay.setOnFinished(event -> Platform.exit());
            delay.play();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
