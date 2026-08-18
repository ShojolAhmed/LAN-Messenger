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
 *
 * <p>The row is <b>live</b>: {@link #refreshUnread()} re-reads the conversation's
 * unread count to show/hide the badge, and {@link #setSubtitle(String)} updates the
 * secondary line (used to show a short preview of the latest message). This lets
 * the sidebar reflect activity without being rebuilt from scratch on every event.
 */
public final class SidebarItem extends HBox {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final Conversation conversation;
    private final Region selectionBar = new Region();
    private final Label subtitle = new Label();
    private final Label badge = new Label();

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

        subtitle.getStyleClass().add("sidebar-item-sub");
        subtitle.setText(conversation.sidebarSubtitle());
        subtitle.setMaxWidth(Double.MAX_VALUE);

        VBox text = new VBox(name, subtitle);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0); // allow the labels to ellipsize instead of overflowing
        HBox.setHgrow(text, Priority.ALWAYS);

        badge.getStyleClass().add("badge");

        getChildren().addAll(avatar, text, badge);
        refreshUnread();

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

    /** Replaces the secondary line (e.g. a short preview of the latest message). */
    public void setSubtitle(String text) {
        subtitle.setText(text == null ? "" : text);
    }

    /**
     * Re-reads {@link Conversation#unread()} and shows the count as a pill, or hides
     * the badge entirely when there is nothing unread.
     */
    public void refreshUnread() {
        int unread = conversation.unread();
        boolean show = unread > 0;
        badge.setText(show ? Integer.toString(unread) : "");
        badge.setVisible(show);
        badge.setManaged(show);
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
