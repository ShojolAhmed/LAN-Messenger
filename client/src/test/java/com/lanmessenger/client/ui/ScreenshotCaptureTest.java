package com.lanmessenger.client.ui;

import com.lanmessenger.client.history.ChatHistory;
import com.lanmessenger.client.ui.model.ChatMessage;
import com.lanmessenger.client.ui.model.Conversation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates the README screenshots by rendering the real JavaFX views and writing
 * PNGs to {@code docs/screenshots/}. It reuses the same view-models and seeding the
 * application uses, so the images always reflect the current UI.
 *
 * <p>This is <b>not</b> part of the normal test run: it is gated behind the
 * {@code LANMSG_SHOTS=1} environment variable so it only executes when explicitly
 * requested (it needs a real display to render JavaFX). Generate the screenshots
 * with, from the project root:
 *
 * <pre>{@code
 *   $env:LANMSG_SHOTS = "1"
 *   mvn test -Dtest=ScreenshotCaptureTest -DfailIfNoTests=false
 *   Remove-Item Env:\LANMSG_SHOTS
 * }</pre>
 *
 * <p>Images are captured with {@link Scene#snapshot} and converted to PNG via a
 * {@link PixelReader} (so no {@code javafx.swing} dependency is required).
 */
@EnabledIfEnvironmentVariable(named = "LANMSG_SHOTS", matches = "1")
class ScreenshotCaptureTest {

    private static final int WIDTH = 1120;
    private static final int HEIGHT = 720;
    private static final Path OUT = resolveScreenshotDir();

    /** A fixed "today at 09:00" base so timestamps and the date divider look tidy. */
    private static final LocalDateTime BASE = LocalDateTime.of(LocalDate.now(), LocalTime.of(9, 0));

    @BeforeAll
    static void startToolkit() throws Exception {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // The JavaFX toolkit is already up (e.g. reused JVM); that is fine.
        }
        Platform.setImplicitExit(false);
        Files.createDirectories(OUT);
    }

    @Test
    void captureConnectScreen() throws Exception {
        capture("connect.png", 700, ConnectView::new);
    }

    @Test
    void captureGlobalChat() throws Exception {
        capture("global-chat.png", 900, () -> {
            MainView view = new MainView("You", t -> { }, (r, t) -> { }, ChatHistory.disabled());
            seedRoster(view);
            view.receiveGlobalMessage("Rahim", "Morning everyone! Server's up on the LAN.", BASE);
            view.receiveGlobalMessage("Karim", "Nice, connecting now.", BASE.plusMinutes(1));
            view.receiveGlobalMessage("You", "Awesome \u2014 glad it's stable.", BASE.plusMinutes(2));
            view.noteUserJoined("Nadia");
            view.receiveGlobalMessage("Nadia", "Hi all, just joined the channel.", BASE.plusMinutes(3));
            // A private message while Global is open raises an unread badge on Rahim's row.
            view.receivePrivateMessage("Rahim", "Hey, are you around for a quick sync?", BASE.plusMinutes(4));
            return view;
        });
    }

    @Test
    void capturePrivateChat() throws Exception {
        capture("private-chat.png", 900, () -> {
            MainView view = new MainView("You", t -> { }, (r, t) -> { }, ChatHistory.disabled());
            seedRoster(view);
            // Build a two-sided direct conversation with Rahim, then open it.
            view.receivePrivateMessage("Rahim", "Hey, are you around for a quick sync?", BASE);
            Conversation dm = openDirectConversation(view, "Rahim");
            dm.messages().add(ChatMessage.outgoing("Yep \u2014 pushing the fix now.", BASE.plusMinutes(1)));
            dm.messages().add(ChatMessage.incoming("Rahim", "Great, ping me when it's live.", BASE.plusMinutes(2)));
            dm.messages().add(ChatMessage.outgoing("Done. Server's back up on port 5000.", BASE.plusMinutes(3)));
            // Re-open so the transcript rebuilds with every message shown.
            invokeOpenConversation(view, dm);
            return view;
        });
    }

    // ---------------------------------------------------------------------
    // Capture plumbing
    // ---------------------------------------------------------------------

    private void capture(String fileName, long settleMillis, Supplier<Parent> rootFactory) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                Parent root = rootFactory.get();
                Scene scene = new Scene(root, WIDTH, HEIGHT);
                Theme.apply(scene);

                Stage stage = new Stage();
                stage.setScene(scene);
                stage.setX(60);
                stage.setY(60);
                stage.show();

                // Force a style + layout pass, then let appear-animations, auto-scroll
                // and font loading settle before snapshotting.
                root.applyCss();
                root.layout();

                PauseTransition settle = new PauseTransition(Duration.millis(settleMillis));
                settle.setOnFinished(event -> {
                    try {
                        writePng(scene, fileName);
                    } catch (Throwable t) {
                        failure.set(t);
                    } finally {
                        stage.hide();
                        done.countDown();
                    }
                });
                settle.play();
            } catch (Throwable t) {
                failure.set(t);
                done.countDown();
            }
        });

        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out rendering " + fileName);
        }
        if (failure.get() != null) {
            throw new RuntimeException("Failed to capture " + fileName, failure.get());
        }
        Path file = OUT.resolve(fileName);
        assertTrue(Files.exists(file) && Files.size(file) > 0, "screenshot was not written: " + file);
        System.out.println("Wrote screenshot: " + file.toAbsolutePath());
    }

    private void writePng(Scene scene, String fileName) throws Exception {
        WritableImage image = scene.snapshot(null);
        int w = (int) Math.round(image.getWidth());
        int h = (int) Math.round(image.getHeight());

        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        int[] pixels = new int[w * h];
        reader.getPixels(0, 0, w, h, WritablePixelFormat.getIntArgbInstance(), pixels, 0, w);
        buffered.setRGB(0, 0, w, h, pixels, 0, w);

        Files.createDirectories(OUT);
        File out = OUT.resolve(fileName).toFile();
        ImageIO.write(buffered, "png", out);
    }

    // ---------------------------------------------------------------------
    // Seeding helpers
    // ---------------------------------------------------------------------

    private static void seedRoster(MainView view) {
        view.setOnlineUsers(java.util.List.of("You", "Rahim", "Karim", "Nadia"));
    }

    /**
     * Returns the (private) direct-message conversation MainView created for
     * {@code peer}, opening it in the chat pane. Uses reflection because the
     * messenger has no public "open this conversation" API; capturing screenshots
     * must not change application behaviour.
     */
    private static Conversation openDirectConversation(MainView view, String peer) {
        try {
            Field field = MainView.class.getDeclaredField("directByPeer");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Conversation> byPeer = (Map<String, Conversation>) field.get(view);
            Conversation conversation = byPeer.get(peer);
            if (conversation == null) {
                throw new IllegalStateException("no direct conversation for " + peer);
            }
            invokeOpenConversation(view, conversation);
            return conversation;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not open direct conversation via reflection", e);
        }
    }

    private static void invokeOpenConversation(MainView view, Conversation conversation) {
        try {
            Method open = MainView.class.getDeclaredMethod("openConversation", Conversation.class);
            open.setAccessible(true);
            open.invoke(view, conversation);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not invoke openConversation via reflection", e);
        }
    }

    // ---------------------------------------------------------------------
    // Output location
    // ---------------------------------------------------------------------

    /** Resolves {@code <repo-root>/docs/screenshots}, robust to the working directory. */
    private static Path resolveScreenshotDir() {
        String override = System.getenv("LANMSG_SHOTS_DIR");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path start = Paths.get("").toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("pom.xml"))
                    && Files.isDirectory(p.resolve("client"))
                    && Files.isDirectory(p.resolve("server"))) {
                return p.resolve("docs").resolve("screenshots");
            }
        }
        return start.resolve("docs").resolve("screenshots");
    }
}
