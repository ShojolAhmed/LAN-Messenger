package com.lanmessenger.client.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * A friendly placeholder shown where content would otherwise be: a circular glyph
 * badge, a title, and a short supporting line.
 *
 * <p>Reused for the "no messages yet" case in {@link MessageListView} and available
 * for any future empty view (e.g. before connecting). Text wraps and stays centered
 * so it reads well at any width.
 */
public final class EmptyState extends VBox {

    private final Label glyph = new Label();
    private final Label title = new Label();
    private final Label subtitle = new Label();

    public EmptyState(String glyphText, String titleText, String subtitleText) {
        getStyleClass().add("empty-state");
        setAlignment(Pos.CENTER);

        glyph.getStyleClass().add("empty-glyph");
        StackPane icon = new StackPane(glyph);
        icon.getStyleClass().add("empty-icon");

        title.getStyleClass().add("empty-title");
        subtitle.getStyleClass().add("empty-sub");
        subtitle.setWrapText(true);

        setValues(glyphText, titleText, subtitleText);
        getChildren().addAll(icon, title, subtitle);
    }

    /** Updates the glyph, title and subtitle in place. */
    public void setValues(String glyphText, String titleText, String subtitleText) {
        glyph.setText(glyphText);
        title.setText(titleText);
        subtitle.setText(subtitleText);
    }
}
