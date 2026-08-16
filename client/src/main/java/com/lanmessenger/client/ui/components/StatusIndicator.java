package com.lanmessenger.client.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/**
 * A compact "pill" showing connection state: a coloured dot plus a short label
 * (for example {@code ● Connected}). Shown in the title bar.
 *
 * <p>The three states map to CSS modifier classes so the colours (success /
 * warning / error) are defined once in {@code theme.css}. In this UI-only phase
 * the indicator is set to a sample state; a later phase drives it from the real
 * {@code ChatClient} lifecycle callbacks.
 */
public final class StatusIndicator extends HBox {

    /** Connection state, each mapped to a CSS modifier and default label. */
    public enum State {
        CONNECTED("status-connected", "Connected"),
        CONNECTING("status-connecting", "Connecting\u2026"),
        DISCONNECTED("status-disconnected", "Disconnected");

        private final String styleClass;
        private final String defaultLabel;

        State(String styleClass, String defaultLabel) {
            this.styleClass = styleClass;
            this.defaultLabel = defaultLabel;
        }
    }

    private final Region dot = new Region();
    private final Label label = new Label();

    public StatusIndicator(State initial) {
        getStyleClass().add("status-indicator");
        setAlignment(Pos.CENTER);
        dot.getStyleClass().add("status-dot");
        label.getStyleClass().add("status-label");
        getChildren().addAll(dot, label);
        setState(initial);
    }

    /** Sets the state and uses that state's default label. */
    public void setState(State state) {
        setState(state, state.defaultLabel);
    }

    /**
     * Sets the state and a custom label (e.g. {@code "Connected to 192.168.0.5"}).
     *
     * @param state the connection state
     * @param text  the label text
     */
    public void setState(State state, String text) {
        getStyleClass().removeAll(
                State.CONNECTED.styleClass,
                State.CONNECTING.styleClass,
                State.DISCONNECTED.styleClass);
        getStyleClass().add(state.styleClass);
        label.setText(text);
    }
}
