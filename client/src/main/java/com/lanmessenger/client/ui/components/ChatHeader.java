package com.lanmessenger.client.ui.components;

import com.lanmessenger.client.ui.model.Conversation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The header of the chat pane: the current conversation's avatar, its title and a
 * subtitle (member count for the global room, or presence for a direct chat), plus
 * a ghost "more" action on the right.
 *
 * <p>{@link #setConversation(Conversation)} rebinds the header to a new
 * conversation; {@link com.lanmessenger.client.ui.MainView} calls it whenever the
 * sidebar selection changes.
 */
public final class ChatHeader extends HBox {

    private final HBox avatarSlot = new HBox();
    private final Label title = new Label();
    private final Label subtitle = new Label();

    public ChatHeader() {
        getStyleClass().addAll("chat-header", "on-surface");
        setAlignment(Pos.CENTER_LEFT);

        avatarSlot.setAlignment(Pos.CENTER);

        title.getStyleClass().add("chat-header-title");
        subtitle.getStyleClass().add("chat-header-sub");
        VBox titles = new VBox(title, subtitle);
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titles, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button more = new Button("\u22EF"); // horizontal ellipsis
        more.getStyleClass().add("icon-button");
        more.setFocusTraversable(false);

        getChildren().addAll(avatarSlot, titles, spacer, more);
    }

    /**
     * Points the header at a conversation, refreshing the avatar, title and
     * subtitle.
     *
     * @param conversation the conversation to display
     */
    public void setConversation(Conversation conversation) {
        Avatar avatar = Avatar.forConversation(conversation).size(Avatar.Size.MEDIUM);
        avatarSlot.getChildren().setAll(avatar);
        title.setText(conversation.title());
        subtitle.setText(conversation.headerSubtitle());
    }
}
