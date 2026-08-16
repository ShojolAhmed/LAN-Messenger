package com.lanmessenger.client.ui.components;

import com.lanmessenger.client.ui.model.Conversation;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * One selectable row in the {@link Sidebar}: an avatar, the conversation title and
 * a secondary line, an optional unread badge, and a slim accent bar that appears
 * when the row is selected.
 *
 * <p>Interactive states are expressed in CSS: {@code :hover} and {@code :focused}
 * are built in, and a custom {@code :selected} pseudo-class is toggled from
 * {@link #setSelected(boolean)}. The row is keyboard-operable (focus traversable;
 * Enter/Space activate it) for accessibility.
 */
public final class SidebarItem extends HBox {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final Conversation conversation;
    private final Region selectionBar = new Region();

    /**
     * @param conversation the conversation this row represents
     * @param onActivate   invoked when the row is clicked or activated by keyboard
     */
    public SidebarItem(Conversation conversation, Consumer<SidebarItem> onActivate) {
        this.conversation = conversation;

        getStyleClass().add("sidebar-item");
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE);
        setFocusTraversable(true);

        // Accent bar is an overlay so it never shifts the content on selection.
        selectionBar.getStyleClass().add("selection-bar");
        selectionBar.setManaged(false);
        getChildren().add(selectionBar);

        Avatar avatar = Avatar.forConversation(conversation).size(Avatar.Size.SMALL);

        Label name = new Label(conversation.title());
        name.getStyleClass().add("sidebar-item-name");
        name.setMaxWidth(Double.MAX_VALUE);

        Label subtitle = new Label(conversation.sidebarSubtitle());
        subtitle.getStyleClass().add("sidebar-item-sub");
        subtitle.setMaxWidth(Double.MAX_VALUE);

        VBox text = new VBox(name, subtitle);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0); // allow the labels to ellipsize instead of overflowing
        HBox.setHgrow(text, Priority.ALWAYS);

        getChildren().addAll(avatar, text);

        if (conversation.unread() > 0) {
            Label badge = new Label(Integer.toString(conversation.unread()));
            badge.getStyleClass().add("badge");
            getChildren().add(badge);
        }

        setOnMouseClicked(event -> {
            requestFocus();
            onActivate.accept(this);
        });
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                onActivate.accept(this);
                event.consume();
            }
        });
    }

    /** @return the conversation this row opens. */
    public Conversation conversation() {
        return conversation;
    }

    /** Toggles the selected look (accent bar, brighter text, raised background). */
    public void setSelected(boolean selected) {
        pseudoClassStateChanged(SELECTED, selected);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // Vertically centre the (unmanaged) accent bar against the left edge.
        selectionBar.autosize();
        double barHeight = Math.max(selectionBar.getHeight(), 20);
        selectionBar.resize(3, barHeight);
        selectionBar.relocate(3, (getHeight() - barHeight) / 2);
    }
}
