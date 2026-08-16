package com.lanmessenger.client.ui.components;

import com.lanmessenger.client.ui.model.ChatMessage;
import com.lanmessenger.client.ui.model.ChatUser;
import javafx.beans.value.ObservableDoubleValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds the node for one message row. A factory (not a subclass) because the
 * three {@link ChatMessage.Kind kinds} produce quite different structures:
 *
 * <ul>
 *   <li><b>incoming</b> — avatar on the left, then author/time meta above a
 *       left-aligned surface bubble;</li>
 *   <li><b>outgoing</b> — right-aligned accent bubble with a time above it;</li>
 *   <li><b>system</b> — a centered, quiet pill.</li>
 * </ul>
 *
 * <p>Consecutive messages from the same author can be <em>grouped</em>: the avatar
 * and meta line are omitted and the bubble tucks in under the previous one, which
 * is what gives the list its calm, chat-app rhythm.
 *
 * <p>Bubbles wrap responsively: the caller passes an observable max-width (a
 * fraction of the viewport) that each bubble binds to, so long messages reflow as
 * the window resizes instead of stretching edge to edge.
 */
public final class MessageBubble {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    /** Width reserved for the (small) avatar so grouped rows stay aligned. */
    private static final double AVATAR_GUTTER = 32;

    private MessageBubble() {
        // Factory only.
    }

    /**
     * @param message       the message to render
     * @param grouped       {@code true} to hide the avatar/meta (same author as above)
     * @param maxBubbleWidth observable cap on bubble width for responsive wrapping
     * @return the row node ready to add to the message list
     */
    public static Node create(ChatMessage message, boolean grouped, ObservableDoubleValue maxBubbleWidth) {
        return switch (message.kind()) {
            case SYSTEM -> system(message);
            case OUTGOING -> outgoing(message, grouped, maxBubbleWidth);
            case INCOMING -> incoming(message, grouped, maxBubbleWidth);
        };
    }

    // ---- Variants --------------------------------------------------------

    private static Node incoming(ChatMessage message, boolean grouped, ObservableDoubleValue maxBubbleWidth) {
        Node avatarArea;
        if (grouped) {
            avatarArea = gutter();
        } else {
            avatarArea = Avatar.forUser(new ChatUser(message.author()))
                    .size(Avatar.Size.SMALL)
                    .withoutPresence();
        }

        VBox content = new VBox();
        content.setAlignment(Pos.TOP_LEFT);
        if (!grouped) {
            content.getChildren().add(meta(message.author(), message.timestamp().format(TIME), Pos.BASELINE_LEFT));
        }
        content.getChildren().add(bubble(message.content(), "bubble-incoming", maxBubbleWidth, Pos.TOP_LEFT));

        HBox row = new HBox(10, avatarArea, content);
        row.getStyleClass().addAll("message-row", "message-row-incoming");
        if (grouped) {
            row.getStyleClass().add("grouped");
        }
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static Node outgoing(ChatMessage message, boolean grouped, ObservableDoubleValue maxBubbleWidth) {
        VBox content = new VBox();
        content.setAlignment(Pos.TOP_RIGHT);
        if (!grouped) {
            content.getChildren().add(meta(null, message.timestamp().format(TIME), Pos.BASELINE_RIGHT));
        }
        content.getChildren().add(bubble(message.content(), "bubble-outgoing", maxBubbleWidth, Pos.TOP_RIGHT));

        HBox row = new HBox(content);
        row.getStyleClass().addAll("message-row", "message-row-outgoing");
        if (grouped) {
            row.getStyleClass().add("grouped");
        }
        row.setAlignment(Pos.TOP_RIGHT);
        return row;
    }

    private static Node system(ChatMessage message) {
        Label text = new Label(message.content());
        text.getStyleClass().add("bubble-text");
        text.setWrapText(true);

        HBox pill = new HBox(text);
        pill.getStyleClass().addAll("bubble", "bubble-system");
        pill.setAlignment(Pos.CENTER);

        HBox row = new HBox(pill);
        row.getStyleClass().addAll("message-row", "message-row-system");
        row.setAlignment(Pos.CENTER);
        return row;
    }

    // ---- Building blocks -------------------------------------------------

    /** A fixed-width invisible stand-in for the avatar column on grouped rows. */
    private static Region gutter() {
        Region gutter = new Region();
        gutter.setMinWidth(AVATAR_GUTTER);
        gutter.setPrefWidth(AVATAR_GUTTER);
        gutter.setMaxWidth(AVATAR_GUTTER);
        return gutter;
    }

    /** The author (optional) + time line shown above a bubble. */
    private static HBox meta(String author, String time, Pos alignment) {
        HBox meta = new HBox();
        meta.getStyleClass().add("message-meta");
        meta.setAlignment(alignment);
        if (author != null && !author.isBlank()) {
            Label authorLabel = new Label(author);
            authorLabel.getStyleClass().add("message-author");
            meta.getChildren().add(authorLabel);
        }
        Label timeLabel = new Label(time);
        timeLabel.getStyleClass().add("message-time");
        meta.getChildren().add(timeLabel);
        return meta;
    }

    /**
     * A rounded bubble that hugs short text but caps at {@code maxBubbleWidth} and
     * wraps beyond it. The inner label grows to fill the (capped) bubble so wrapping
     * happens at the intended width.
     */
    private static HBox bubble(String content, String variantClass,
                               ObservableDoubleValue maxBubbleWidth, Pos alignment) {
        Label text = new Label(content);
        text.getStyleClass().add("bubble-text");
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox bubble = new HBox(text);
        bubble.getStyleClass().addAll("bubble", variantClass);
        bubble.setAlignment(alignment);
        bubble.maxWidthProperty().bind(maxBubbleWidth);
        return bubble;
    }
}
