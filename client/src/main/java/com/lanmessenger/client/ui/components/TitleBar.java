package com.lanmessenger.client.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * The top application bar: a small brand mark and the app name on the left, and a
 * {@link StatusIndicator} pinned to the right.
 *
 * <p>This is the in-window chrome from the design mock ({@code LAN Messenger …
 * ● Connected}); it sits above the sidebar/chat split.
 */
public final class TitleBar extends HBox {

    private final StatusIndicator status = new StatusIndicator(StatusIndicator.State.CONNECTED);

    /**
     * @param appName the application name to display
     */
    public TitleBar(String appName) {
        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);

        Label glyph = new Label("L");
        glyph.getStyleClass().add("brand-glyph");
        StackPane mark = new StackPane(glyph);
        mark.getStyleClass().add("brand-mark");

        Label name = new Label(appName);
        name.getStyleClass().add("brand-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(mark, name, spacer, status);
    }

    /** @return the connection status indicator, so callers can update its state. */
    public StatusIndicator status() {
        return status;
    }
}
