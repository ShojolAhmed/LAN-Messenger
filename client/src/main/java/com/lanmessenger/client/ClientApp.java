package com.lanmessenger.client;

import com.lanmessenger.client.ui.MainView;
import com.lanmessenger.client.ui.Theme;
import com.lanmessenger.common.Protocol;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * JavaFX entry point for the LAN Messenger client.
 *
 * <p>This phase establishes the visual foundation: it builds the modern messenger
 * shell ({@link MainView}) — title bar, sidebar, chat header, message transcript
 * and composer — styled entirely from {@code theme.css}. The UI runs on sample
 * data and is deliberately <b>not</b> wired to the {@code client.net} networking
 * layer yet; that connection is a later phase.
 */
public class ClientApp extends Application {

    @Override
    public void start(Stage stage) {
        MainView root = new MainView();

        Scene scene = new Scene(root, 1080, 720);
        Theme.apply(scene);

        stage.setTitle(Protocol.APP_NAME);
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.show();

        root.focusComposer();
        System.out.println(Protocol.APP_NAME + " client UI started");

        // When LANMSG_SMOKE=1, exercise the layout at several window sizes and then
        // close automatically, so verification runs neither block on the GUI nor
        // miss layout/binding problems that only appear at certain sizes.
        if ("1".equals(System.getenv("LANMSG_SMOKE"))) {
            runLayoutSmokeTest(stage, root);
        }
    }

    /**
     * Cycles the window through a range of sizes on the live scene, forcing a CSS
     * and layout pass at each, then exits. Any exception thrown during layout will
     * surface on the JavaFX thread and fail the run, which makes this a cheap guard
     * against size-dependent layout or binding regressions.
     */
    private void runLayoutSmokeTest(Stage stage, MainView root) {
        double[][] sizes = {
                {820, 560},   // minimum supported
                {1024, 680},
                {1440, 900},  // large
                {900, 600}    // back to a mid size
        };

        Timeline timeline = new Timeline();
        Duration at = Duration.millis(400);
        for (double[] size : sizes) {
            final double w = size[0];
            final double h = size[1];
            timeline.getKeyFrames().add(new KeyFrame(at, event -> {
                stage.setWidth(w);
                stage.setHeight(h);
                stage.centerOnScreen();
                root.applyCss();
                root.layout();
                System.out.println("Smoke: laid out at " + (int) w + "x" + (int) h);
            }));
            at = at.add(Duration.millis(350));
        }
        timeline.getKeyFrames().add(new KeyFrame(at, event -> Platform.exit()));
        timeline.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
