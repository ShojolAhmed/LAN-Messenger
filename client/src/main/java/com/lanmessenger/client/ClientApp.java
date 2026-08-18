package com.lanmessenger.client;

import com.lanmessenger.client.ui.ConnectView;
import com.lanmessenger.client.ui.MainView;
import com.lanmessenger.client.ui.Theme;
import com.lanmessenger.common.Protocol;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JavaFX entry point for the LAN Messenger client.
 *
 * <p>The app opens on the {@link ClientController connection screen}: the user
 * enters a username, server IP and port, and on a successful login the controller
 * transitions to the messenger shell ({@link MainView}) — title bar, sidebar, chat
 * header, message transcript and composer — all styled from {@code theme.css}.
 * The controller runs the blocking connect on a background task so the JavaFX
 * Application Thread never freezes while connecting.
 */
public class ClientApp extends Application {

    @Override
    public void start(Stage stage) {
        // When LANMSG_SMOKE=1, exercise both screens (connect + messenger) at a
        // range of window sizes, then close — a cheap layout regression guard that
        // neither blocks on the GUI nor requires a running server.
        if ("1".equals(System.getenv("LANMSG_SMOKE"))) {
            startSmokeTest(stage);
            return;
        }

        ClientController controller = new ClientController();
        Scene scene = new Scene(controller.root(), 1080, 720);
        Theme.apply(scene);
        controller.attachScene(scene);

        stage.setTitle(Protocol.APP_NAME);
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.show();

        controller.start();
        System.out.println(Protocol.APP_NAME + " client UI started");
    }

    private void startSmokeTest(Stage stage) {
        ConnectView connectView = new ConnectView();
        MainView mainView = new MainView("You", text -> { }, (recipient, text) -> { });
        seedSmokeTestChat(mainView);

        Scene scene = new Scene(connectView, 1080, 720);
        Theme.apply(scene);

        stage.setTitle(Protocol.APP_NAME);
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.show();

        runLayoutSmokeTest(stage, scene, connectView, mainView);
    }

    /**
     * Populates the messenger with a few messages through its real inbound API so
     * the layout check exercises incoming, outgoing and system bubbles (and the
     * live online count) exactly as a running session would.
     */
    private void seedSmokeTestChat(MainView mainView) {
        LocalDateTime now = LocalDateTime.now();
        mainView.setOnlineUsers(List.of("You", "Rahim", "Karim", "Sakib"));
        mainView.receiveGlobalMessage("Rahim", "Morning everyone! Server's up on the LAN.", now);
        mainView.receiveGlobalMessage("Karim", "Nice, connecting now.", now);
        mainView.receiveGlobalMessage("You", "Awesome \u2014 glad it's stable.", now);
        mainView.noteUserJoined("Nadia");
        mainView.receiveGlobalMessage("Nadia", "Hi all, just joined the channel.", now);
        // A private message while Global is open raises an unread badge on Rahim's row.
        mainView.receivePrivateMessage("Rahim", "Hey, are you around for a quick sync?", now);
    }

    /**
     * Shows each supplied root in turn and cycles the window through a range of
     * sizes, forcing a CSS and layout pass at each, then exits. Any exception
     * thrown during layout surfaces on the JavaFX thread and fails the run, which
     * makes this a cheap guard against size-dependent layout or binding regressions
     * across both the connect screen and the messenger shell.
     */
    private void runLayoutSmokeTest(Stage stage, Scene scene, Parent... roots) {
        double[][] sizes = {
                {820, 560},   // minimum supported
                {1024, 680},
                {1440, 900},  // large
                {900, 600}    // back to a mid size
        };

        Timeline timeline = new Timeline();
        Duration at = Duration.millis(300);
        for (Parent root : roots) {
            timeline.getKeyFrames().add(new KeyFrame(at, event -> scene.setRoot(root)));
            at = at.add(Duration.millis(150));
            for (double[] size : sizes) {
                final double w = size[0];
                final double h = size[1];
                timeline.getKeyFrames().add(new KeyFrame(at, event -> {
                    stage.setWidth(w);
                    stage.setHeight(h);
                    stage.centerOnScreen();
                    root.applyCss();
                    root.layout();
                    System.out.println("Smoke: " + root.getClass().getSimpleName()
                            + " laid out at " + (int) w + "x" + (int) h);
                }));
                at = at.add(Duration.millis(250));
            }
        }
        timeline.getKeyFrames().add(new KeyFrame(at, event -> Platform.exit()));
        timeline.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
