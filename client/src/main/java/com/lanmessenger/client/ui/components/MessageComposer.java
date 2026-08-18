package com.lanmessenger.client.ui.components;

import com.lanmessenger.common.Protocol;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.function.Consumer;

/**
 * The bottom compose bar: a rounded, growing input area and a primary send button.
 *
 * <p>Interaction:
 * <ul>
 *   <li>the send button is <b>disabled</b> while the input is empty or whitespace;</li>
 *   <li>pressing <b>Enter</b> (or clicking <b>Send</b>) submits when non-blank;</li>
 *   <li>pressing <b>Shift+Enter</b> inserts a newline for multi-line messages;</li>
 *   <li>the input grows with its content up to {@link #MAX_ROWS} rows, then scrolls;</li>
 *   <li>typing is capped at {@link Protocol#MAX_MESSAGE_LENGTH} characters so an
 *       extremely long message can never be composed (the server caps too);</li>
 *   <li>after sending, the input clears and keeps focus for a fluid back-and-forth.</li>
 * </ul>
 *
 * <p>The submitted text (trimmed, non-blank) is handed to the callback set via
 * {@link #setOnSend(Consumer)}. {@link com.lanmessenger.client.ui.MainView} sends
 * it to the server as a global message and echoes it into the transcript.
 */
public final class MessageComposer extends VBox {

    /** Grow the input up to this many rows before it starts scrolling. */
    private static final int MAX_ROWS = 5;

    private final TextArea input = new TextArea();
    private final Button send = new Button();

    private Consumer<String> onSend = text -> { };

    public MessageComposer() {
        getStyleClass().add("composer-bar");

        input.getStyleClass().addAll("composer-input", "text-input");
        input.setPromptText("Type a message\u2026");
        input.setAccessibleText("Message input");
        input.setWrapText(true);
        input.setPrefRowCount(1);
        // Cap the length at the source; the server truncates as a safety net too.
        input.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= Protocol.MAX_MESSAGE_LENGTH ? change : null));
        HBox.setHgrow(input, Priority.ALWAYS);

        // Enter submits; Shift+Enter falls through so the TextArea inserts a newline.
        input.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                fire();
            }
        });
        // Grow with the content up to MAX_ROWS, then let the input scroll.
        input.textProperty().addListener((obs, old, text) -> adjustRows(text));

        // A crisp, font-independent paper-plane "send" glyph (classic 24x24 path).
        // It is tinted via CSS (.send-icon) so the colour still lives in theme.css.
        SVGPath sendIcon = new SVGPath();
        sendIcon.setContent("M2 21l21-9L2 3v7l15 2-15 2v7z");
        sendIcon.getStyleClass().add("send-icon");
        sendIcon.setScaleX(0.82);
        sendIcon.setScaleY(0.82);

        send.setGraphic(sendIcon);
        send.getStyleClass().add("send-button");
        send.setAccessibleText("Send message");
        send.setTooltip(new Tooltip("Send"));
        // Disable while there is nothing meaningful to send.
        send.disableProperty().bind(
                Bindings.createBooleanBinding(() -> input.getText().isBlank(), input.textProperty()));
        send.setOnAction(event -> fire());

        HBox pill = new HBox(input, send);
        pill.getStyleClass().add("composer");
        pill.setAlignment(Pos.BOTTOM_CENTER);

        getChildren().add(pill);
    }

    /** Registers the callback invoked with the (non-blank, trimmed) message text. */
    public void setOnSend(Consumer<String> callback) {
        this.onSend = callback == null ? text -> { } : callback;
    }

    /** Sets the input's placeholder text (e.g. to name the active conversation). */
    public void setPrompt(String prompt) {
        input.setPromptText(prompt == null || prompt.isBlank() ? "Type a message\u2026" : prompt);
    }

    /** Moves keyboard focus to the input. */
    public void focusInput() {
        input.requestFocus();
    }

    /** Sizes the input to its line count, clamped between one and {@link #MAX_ROWS}. */
    private void adjustRows(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        input.setPrefRowCount(Math.min(Math.max(lines, 1), MAX_ROWS));
    }

    private void fire() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        onSend.accept(text);
        input.clear();
        input.requestFocus();
    }
}
