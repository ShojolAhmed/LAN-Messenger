package com.lanmessenger.client.ui;

import com.lanmessenger.client.ui.components.ChatHeader;
import com.lanmessenger.client.ui.components.MessageComposer;
import com.lanmessenger.client.ui.components.MessageListView;
import com.lanmessenger.client.ui.components.Sidebar;
import com.lanmessenger.client.ui.components.TitleBar;
import com.lanmessenger.client.ui.model.ChatMessage;
import com.lanmessenger.client.ui.model.Conversation;
import com.lanmessenger.common.Protocol;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The root of the messenger UI: the title bar on top, and below it a two-column
 * split of the {@link Sidebar} and the chat pane (header, transcript, composer).
 *
 * <p>This class is pure composition and wiring; every visual decision lives in the
 * reusable components and {@code theme.css}. It connects them with sample data:
 * selecting a sidebar row swaps the header and transcript, and sending from the
 * composer appends a local message to the open conversation. <b>No networking is
 * involved</b> — a later phase will feed these same components from
 * {@code client.net}.
 */
public final class MainView extends BorderPane {

    private final ChatHeader chatHeader = new ChatHeader();
    private final MessageListView messageList = new MessageListView();
    private final MessageComposer composer = new MessageComposer();

    private Conversation current;

    public MainView() {
        getStyleClass().add("app-root");

        List<Conversation> conversations = SampleData.conversations();
        Sidebar sidebar = new Sidebar(conversations);

        // Chat column: header (fixed), transcript (grows), composer (fixed).
        VBox chatPane = new VBox(chatHeader, messageList, composer);
        chatPane.getStyleClass().add("chat-pane");
        VBox.setVgrow(messageList, Priority.ALWAYS);
        HBox.setHgrow(chatPane, Priority.ALWAYS);

        HBox split = new HBox(sidebar, chatPane);
        split.getStyleClass().add("content-split");

        setTop(new TitleBar(Protocol.APP_NAME));
        setCenter(split);

        // Wire interactions (sample-data only).
        sidebar.setOnConversationSelected(this::openConversation);
        composer.setOnSend(this::sendLocal);

        sidebar.selectFirst(); // opens the global room by default
    }

    /** Focuses the composer input; call once the window is shown. */
    public void focusComposer() {
        composer.focusInput();
    }

    private void openConversation(Conversation conversation) {
        current = conversation;
        chatHeader.setConversation(conversation);
        messageList.setConversation(conversation);
    }

    private void sendLocal(String text) {
        if (current != null) {
            current.messages().add(ChatMessage.outgoing(text, LocalDateTime.now()));
        }
    }
}
