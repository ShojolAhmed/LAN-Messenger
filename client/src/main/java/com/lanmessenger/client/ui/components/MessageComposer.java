package com.lanmessenger.client.ui.components;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * The bottom compose bar: a rounded input pill and a primary send button.
 *
 * <p>Interaction:
 * <ul>
 *   <li>the send button is <b>disabled</b> while the input is empty or whitespace;</li>
 *   <li>pressing <b>Enter</b> or clicking <b>Send</b> submits (when non-blank);</li>
 *   <li>after sending, the input clears and keeps focus for a fluid back-and-forth.</li>
 * </ul>
 *
 * <p>The submitted text is handed to the callback set via
 * {@link #setOnSend(Consumer)}. In this UI phase {@link com.lanmessenger.client.ui.MainView}
 * simply appends it to the open conversation's sample messages — no networking is
 * involved.
 */
public final class MessageComposer extends VBox {

    private final TextField input = new TextField();
    private final Button send = new Button("Send");

    private Consumer<String> onSend = text -> { };

    public MessageComposer() {
        getStyleClass().add("composer-bar");

        input.getStyleClass().addAll("composer-input", "text-input");
        input.setPromptText("Type a message\u2026");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(event -> fire()); // Enter submits

        send.getStyleClass().add("send-button");
        // Disable while there is nothing meaningful to send.
        send.disableProperty().bind(
                Bindings.createBooleanBinding(() -> input.getText().isBlank(), input.textProperty()));
        send.setOnAction(event -> fire());

        HBox pill = new HBox(input, send);
        pill.getStyleClass().add("composer");
        pill.setAlignment(Pos.CENTER);

        getChildren().add(pill);
    }

    /** Registers the callback invoked with the (non-blank, trimmed) message text. */
    public void setOnSend(Consumer<String> callback) {
        this.onSend = callback == null ? text -> { } : callback;
    }

    /** Moves keyboard focus to the input. */
    public void focusInput() {
        input.requestFocus();
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
