package com.lanmessenger.client.ui.components;

import com.lanmessenger.client.ui.model.ChatMessage;
import com.lanmessenger.client.ui.model.Conversation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * The scrollable transcript of a conversation.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>renders each {@link ChatMessage} via {@link MessageBubble}, inserting date
 *       dividers between days and <em>grouping</em> consecutive messages from the
 *       same author;</li>
 *   <li>binds to the conversation's live message list so appends (e.g. from the
 *       composer) show up automatically, with a subtle fade-in and an auto-scroll
 *       to the newest message;</li>
 *   <li>shows an {@link EmptyState} when a conversation has no messages.</li>
 * </ul>
 *
 * <p>Bubble width is capped at a fraction of this view's width and re-evaluated on
 * resize, so the transcript stays readable from the minimum window size up to a
 * maximised window.
 */
public final class MessageListView extends StackPane {

    /** Fraction of the viewport a single bubble may occupy. */
    private static final double BUBBLE_WIDTH_RATIO = 0.70;
    /** Messages within this window from the same author are visually grouped. */
    private static final long GROUP_WINDOW_MINUTES = 5;

    private static final DateTimeFormatter DIVIDER_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH);

    private final VBox messagesBox = new VBox();
    private final Region topSpacer = new Region();
    private final ScrollPane scroll = new ScrollPane();
    private final EmptyState emptyState =
            new EmptyState("\u2709", "No messages yet", "Send a message below to start the conversation.");
    private final DoubleProperty maxBubbleWidth = new SimpleDoubleProperty();

    private Conversation current;
    private final ListChangeListener<ChatMessage> messagesListener = this::onMessagesChanged;

    public MessageListView() {
        messagesBox.getStyleClass().add("messages-box");

        // A growing top spacer anchors the transcript to the bottom while it is
        // shorter than the viewport (new messages sit just above the composer), yet
        // still lets it scroll naturally once it overflows.
        VBox.setVgrow(topSpacer, Priority.ALWAYS);

        // Cap bubble width to a fraction of the viewport (with a sensible floor),
        // recomputed on resize so long messages reflow instead of overflowing.
        maxBubbleWidth.bind(Bindings.max(200, widthProperty().multiply(BUBBLE_WIDTH_RATIO)));

        scroll.setContent(messagesBox);
        scroll.getStyleClass().add("messages-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // Keep the content at least as tall as the viewport so the spacer can push
        // messages to the bottom (without ever clipping a long transcript).
        scroll.viewportBoundsProperty().addListener((obs, old, bounds) ->
                messagesBox.setMinHeight(bounds.getHeight()));

        emptyState.setVisible(false);
        emptyState.setManaged(false);

        getChildren().addAll(scroll, emptyState);
    }

    /**
     * Points the view at a conversation: unsubscribes from the previous one,
     * rebuilds the transcript, and subscribes for future appends.
     *
     * @param conversation the conversation to display
     */
    public void setConversation(Conversation conversation) {
        if (current != null) {
            current.messages().removeListener(messagesListener);
        }
        current = conversation;
        rebuild();
        current.messages().addListener(messagesListener);
        updateEmptyState();
        scrollToBottomLater();
    }

    // ---- List maintenance ------------------------------------------------

    private void onMessagesChanged(ListChangeListener.Change<? extends ChatMessage> change) {
        while (change.next()) {
            if (change.wasAdded() && !change.wasRemoved()) {
                for (int i = change.getFrom(); i < change.getTo(); i++) {
                    appendRow(i, true);
                }
            } else {
                rebuild();
            }
        }
        updateEmptyState();
    }

    private void rebuild() {
        // Reset to just the anchoring spacer, then render every message.
        messagesBox.getChildren().setAll(topSpacer);
        if (current == null) {
            return;
        }
        for (int i = 0; i < current.messages().size(); i++) {
            appendRow(i, false);
        }
    }

    /**
     * Appends the row for {@code index}, first adding a date divider if this
     * message starts a new day, and grouping it under the previous one where
     * appropriate.
     */
    private void appendRow(int index, boolean animate) {
        ChatMessage message = current.messages().get(index);
        ChatMessage previous = index > 0 ? current.messages().get(index - 1) : null;

        boolean newDay = previous == null
                || !previous.timestamp().toLocalDate().equals(message.timestamp().toLocalDate());
        if (newDay) {
            messagesBox.getChildren().add(dateDivider(message.timestamp().toLocalDate()));
        }

        boolean grouped = !newDay && isGrouped(previous, message);
        Node row = MessageBubble.create(message, grouped, maxBubbleWidth);
        messagesBox.getChildren().add(row);

        if (animate) {
            playAppear(row);
        }
        scrollToBottomLater();
    }

    private static boolean isGrouped(ChatMessage previous, ChatMessage current) {
        if (previous == null || previous.isSystem() || current.isSystem()) {
            return false;
        }
        if (previous.kind() != current.kind() || !previous.author().equals(current.author())) {
            return false;
        }
        long minutes = Math.abs(ChronoUnit.MINUTES.between(previous.timestamp(), current.timestamp()));
        return minutes <= GROUP_WINDOW_MINUTES;
    }

    // ---- Empty state + scrolling ----------------------------------------

    private void updateEmptyState() {
        boolean empty = current == null || current.messages().isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        scroll.setVisible(!empty);
    }

    private void scrollToBottomLater() {
        // Defer until after layout so the new content height is known.
        Platform.runLater(() -> scroll.setVvalue(1.0));
    }

    // ---- Node builders ---------------------------------------------------

    private Node dateDivider(LocalDate date) {
        Region left = dividerLine();
        Region right = dividerLine();
        Label label = new Label(formatDivider(date));
        label.getStyleClass().add("divider-label");

        HBox divider = new HBox(left, label, right);
        divider.getStyleClass().add("date-divider");
        divider.setAlignment(Pos.CENTER);
        return divider;
    }

    private static Region dividerLine() {
        Region line = new Region();
        line.getStyleClass().add("divider-line");
        HBox.setHgrow(line, Priority.ALWAYS);
        return line;
    }

    private static String formatDivider(LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.equals(today)) {
            return "Today";
        }
        if (date.equals(today.minusDays(1))) {
            return "Yesterday";
        }
        return date.format(DIVIDER_FORMAT);
    }

    private static void playAppear(Node node) {
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(160), node);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition rise = new TranslateTransition(Duration.millis(160), node);
        rise.setFromY(8);
        rise.setToY(0);
        rise.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, rise).play();
    }
}
