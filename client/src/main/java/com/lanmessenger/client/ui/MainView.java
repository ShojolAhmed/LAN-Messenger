package com.lanmessenger.client.ui;

import com.lanmessenger.client.ui.components.ChatHeader;
import com.lanmessenger.client.ui.components.MessageComposer;
import com.lanmessenger.client.ui.components.MessageListView;
import com.lanmessenger.client.ui.components.Sidebar;
import com.lanmessenger.client.ui.components.StatusIndicator;
import com.lanmessenger.client.ui.components.TitleBar;
import com.lanmessenger.client.ui.model.ChatMessage;
import com.lanmessenger.client.ui.model.ChatUser;
import com.lanmessenger.client.ui.model.Conversation;
import com.lanmessenger.client.ui.model.ConversationKind;
import com.lanmessenger.common.Protocol;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The root of the messenger UI: the title bar on top, and below it a two-column
 * split of the {@link Sidebar} and the chat pane (header, transcript, composer).
 *
 * <p>This class is pure composition and wiring; every visual decision lives in the
 * reusable components and {@code theme.css}. It hosts <b>two kinds of
 * conversation</b> that share one chat pane:
 * <ul>
 *   <li>the single shared <b>Global</b> room every logged-in user is in; and</li>
 *   <li>a one-to-one <b>direct message</b> per other online user, created on demand
 *       and kept in memory with its own independent history.</li>
 * </ul>
 *
 * <h2>Roster &amp; presence</h2>
 * <p>{@link #setOnlineUsers(List)}, {@link #noteUserJoined(String)} and
 * {@link #noteUserLeft(String)} keep the sidebar's ONLINE list and the header's
 * count in step with the server, adding and removing direct-message rows as people
 * come and go. Your own name is never shown as a private-chat recipient.
 *
 * <h2>Messaging</h2>
 * <ul>
 *   <li><b>Sending</b> — the composer routes to the <em>active</em> conversation:
 *       global text goes to {@link #onSendGlobal}; a direct message goes to
 *       {@link #onSendPrivate} for that peer only. Either way it is echoed into the
 *       right transcript immediately, because the server never echoes a message
 *       back to its sender.</li>
 *   <li><b>Receiving</b> — {@link #receiveGlobalMessage(String, String, LocalDateTime)}
 *       and {@link #receivePrivateMessage(String, String, LocalDateTime)} append to
 *       the matching conversation; a message that lands in a conversation you are
 *       not currently viewing raises an unread badge on its sidebar row.</li>
 *   <li><b>Delivery problems</b> — {@link #notePrivateDeliveryFailure(String, String)}
 *       drops a quiet system notice into the affected conversation.</li>
 * </ul>
 *
 * <p>All of these methods must be called on the JavaFX Application Thread; the
 * controller already marshals the networking callbacks onto it.
 */
public final class MainView extends BorderPane {

    private static final String GLOBAL_ROOM_TITLE = "Global Chat";
    private static final String GLOBAL_ROOM_ID = "global";
    /** Maximum characters shown in a sidebar row's latest-message preview. */
    private static final int PREVIEW_LENGTH = 34;

    private final TitleBar titleBar = new TitleBar(Protocol.APP_NAME);
    private final ChatHeader chatHeader = new ChatHeader();
    private final MessageListView messageList = new MessageListView();
    private final MessageComposer composer = new MessageComposer();
    private final Sidebar sidebar;

    private final String currentUsername;
    private final Consumer<String> onSendGlobal;
    private final BiConsumer<String, String> onSendPrivate;

    private final Conversation global;
    /** peer username &rarr; their direct conversation (kept even while they are offline). */
    private final Map<String, Conversation> directByPeer = new LinkedHashMap<>();
    /** Currently online usernames, including ourselves; drives the roster and count. */
    private final Set<String> onlineUsers = new TreeSet<>();

    private Conversation active;

    /**
     * @param currentUsername the logged-in user's name, used to tell our own
     *                        messages apart and to exclude ourselves from the roster
     * @param onSendGlobal    where composed global messages are sent (typically
     *                        {@code ChatClient::sendGlobalMessage})
     * @param onSendPrivate   where composed private messages are sent as
     *                        {@code (recipient, text)} (typically
     *                        {@code ChatClient::sendPrivateMessage})
     */
    public MainView(String currentUsername,
                    Consumer<String> onSendGlobal,
                    BiConsumer<String, String> onSendPrivate) {
        this.currentUsername = currentUsername == null ? "" : currentUsername;
        this.onSendGlobal = onSendGlobal == null ? text -> { } : onSendGlobal;
        this.onSendPrivate = onSendPrivate == null ? (to, text) -> { } : onSendPrivate;

        getStyleClass().add("app-root");

        global = Conversation.builder(GLOBAL_ROOM_ID, ConversationKind.GLOBAL, GLOBAL_ROOM_TITLE)
                .headerSubtitle("Everyone on the LAN")
                .sidebarSubtitle("Public channel \u00b7 everyone")
                .messages(List.of(ChatMessage.system(
                        "This is the beginning of the Global channel.", LocalDateTime.now())))
                .build();

        sidebar = new Sidebar(global);

        // Chat column: header (fixed), transcript (grows), composer (fixed).
        VBox chatPane = new VBox(chatHeader, messageList, composer);
        chatPane.getStyleClass().add("chat-pane");
        VBox.setVgrow(messageList, Priority.ALWAYS);
        HBox.setHgrow(chatPane, Priority.ALWAYS);

        HBox split = new HBox(sidebar, chatPane);
        split.getStyleClass().add("content-split");

        setTop(titleBar);
        setCenter(split);

        sidebar.setOnConversationSelected(this::openConversation);
        composer.setOnSend(this::sendActive);

        // Seed the roster with ourselves so the count is sensible before the first
        // USER_LIST arrives from the server.
        if (!this.currentUsername.isEmpty()) {
            onlineUsers.add(this.currentUsername);
        }
        refreshOnlinePeers();
        sidebar.selectGlobal(); // opens the global room via the selection callback
    }

    /** Focuses the composer input; call once the window is shown. */
    public void focusComposer() {
        composer.focusInput();
    }

    /** Reflects a live connection in the title bar's status pill. */
    public void showConnected() {
        titleBar.status().setState(StatusIndicator.State.CONNECTED);
    }

    /** Reflects a lost/closed connection in the title bar's status pill. */
    public void showDisconnected() {
        titleBar.status().setState(StatusIndicator.State.DISCONNECTED);
    }

    // ---------------------------------------------------------------------
    // Inbound messages
    // ---------------------------------------------------------------------

    /**
     * Appends a received global message to the Global transcript. A message from
     * another user is shown as an incoming bubble; one whose {@code sender} is us
     * renders as our own bubble (a defensive case — the server does not echo a
     * sender's own broadcast, which is why {@link #sendActive(String)} shows it
     * locally instead).
     *
     * @param sender  the message author as stamped by the server
     * @param content the message text
     * @param at      when the message was received
     */
    public void receiveGlobalMessage(String sender, String content, LocalDateTime at) {
        boolean own = isSelf(sender);
        ChatMessage message = own
                ? ChatMessage.outgoing(content, at)
                : ChatMessage.incoming(sender, content, at);
        deliverInto(global, message, own);
    }

    /**
     * Appends a received private message to the conversation with {@code sender},
     * creating that conversation (and its sidebar row) if it does not exist yet.
     *
     * @param sender  the peer who sent the message (as stamped by the server)
     * @param content the message text
     * @param at      when the message was received
     */
    public void receivePrivateMessage(String sender, String content, LocalDateTime at) {
        if (sender == null || sender.isBlank() || isSelf(sender)) {
            return; // nothing sensible to show; the server never echoes to a sender
        }
        // A peer we can hear from is online; make sure they have a row to badge.
        if (onlineUsers.add(sender)) {
            refreshOnlinePeers();
        }
        Conversation dm = directConversation(sender);
        deliverInto(dm, ChatMessage.incoming(sender, content, at), false);
    }

    /**
     * Notes that a private message could not be delivered (for example the
     * recipient went offline), as a quiet system line in that conversation.
     *
     * @param recipient the user the message was aimed at
     * @param detail    the server's explanation (used as a fallback)
     */
    public void notePrivateDeliveryFailure(String recipient, String detail) {
        if (recipient == null || recipient.isBlank() || isSelf(recipient)) {
            return;
        }
        Conversation dm = directConversation(recipient);
        String note = "Couldn\u2019t deliver your message to " + recipient
                + " \u2014 they may have gone offline.";
        dm.messages().add(ChatMessage.system(note, LocalDateTime.now()));
        // A delivery failure is feedback about our own action, not new unread traffic.
    }

    // ---------------------------------------------------------------------
    // Presence
    // ---------------------------------------------------------------------

    /** Records that {@code username} joined: updates the roster, count and a notice. */
    public void noteUserJoined(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        boolean added = onlineUsers.add(username);
        if (added && !isSelf(username)) {
            refreshOnlinePeers();
        }
        global.messages().add(ChatMessage.system(username + " joined the channel.", LocalDateTime.now()));
        updateGlobalHeaderSubtitle();
    }

    /** Records that {@code username} left: updates the roster, count and a notice. */
    public void noteUserLeft(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        boolean wasActivePeer = isDirectWith(active, username);
        onlineUsers.remove(username);
        refreshOnlinePeers();

        // Keep the conversation (and its history) in memory in case they return.
        Conversation dm = directByPeer.get(username);
        if (dm != null) {
            dm.messages().add(ChatMessage.system(username + " went offline.", LocalDateTime.now()));
        }
        global.messages().add(ChatMessage.system(username + " left the channel.", LocalDateTime.now()));
        updateGlobalHeaderSubtitle();

        // If we were viewing that peer, their row is gone — fall back to Global so a
        // conversation stays selected and highlighted.
        if (wasActivePeer) {
            sidebar.selectGlobal();
        }
    }

    /**
     * Replaces the known roster with the server's authoritative list (always
     * including ourselves), rebuilds the online peers and refreshes the count.
     *
     * @param usernames the current online usernames
     */
    public void setOnlineUsers(List<String> usernames) {
        onlineUsers.clear();
        if (usernames != null) {
            onlineUsers.addAll(usernames);
        }
        if (!currentUsername.isEmpty()) {
            onlineUsers.add(currentUsername);
        }
        boolean activePeerGone = active != null
                && active.kind() == ConversationKind.DIRECT
                && active.peer() != null
                && !onlineUsers.contains(active.peer().name());

        refreshOnlinePeers();
        updateGlobalHeaderSubtitle();

        if (activePeerGone) {
            sidebar.selectGlobal();
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Opens a conversation in the chat pane and clears its unread badge. */
    private void openConversation(Conversation conversation) {
        active = conversation;
        chatHeader.setConversation(conversation);
        messageList.setConversation(conversation);

        if (conversation.isGlobal()) {
            updateGlobalHeaderSubtitle();
            composer.setPrompt("Message everyone\u2026");
        } else {
            String peer = conversation.peer() != null ? conversation.peer().name() : conversation.title();
            chatHeader.setSubtitle(onlineUsers.contains(peer) ? "Online" : "Offline");
            composer.setPrompt("Message " + peer + "\u2026");
        }

        if (conversation.unread() > 0) {
            conversation.setUnread(0);
            sidebar.refreshUnread(conversation);
        }
    }

    /** Routes composed text to the active conversation (global broadcast or DM). */
    private void sendActive(String text) {
        if (active == null) {
            return;
        }
        if (active.isGlobal()) {
            onSendGlobal.accept(text);
        } else if (active.peer() != null) {
            onSendPrivate.accept(active.peer().name(), text);
        }
        // Echo locally: the server never echoes a message back to its sender.
        deliverInto(active, ChatMessage.outgoing(text, LocalDateTime.now()), true);
    }

    /**
     * Appends {@code message} to {@code conversation}, updates its sidebar preview,
     * and — unless the conversation is currently open — raises its unread badge.
     *
     * @param own {@code true} if this is our own (outgoing) message
     */
    private void deliverInto(Conversation conversation, ChatMessage message, boolean own) {
        conversation.messages().add(message);
        sidebar.setPreview(conversation, preview(message));
        if (!own && conversation != active) {
            conversation.setUnread(conversation.unread() + 1);
            sidebar.refreshUnread(conversation);
        }
    }

    /** Rebuilds the sidebar's ONLINE list from the current roster (excluding self). */
    private void refreshOnlinePeers() {
        List<Conversation> peers = new ArrayList<>();
        for (String name : onlineUsers) { // TreeSet: already alphabetical
            if (!isSelf(name)) {
                peers.add(directConversation(name));
            }
        }
        sidebar.setOnlinePeers(peers);
    }

    /** Gets, or lazily creates, the direct conversation with {@code peer}. */
    private Conversation directConversation(String peer) {
        return directByPeer.computeIfAbsent(peer, name -> Conversation.builder(
                        "dm:" + name, ConversationKind.DIRECT, name)
                .peer(new ChatUser(name))
                .headerSubtitle("Private conversation")
                .sidebarSubtitle("Direct message")
                .messages(List.of(ChatMessage.system(
                        "This is the beginning of your private conversation with " + name + ".",
                        LocalDateTime.now())))
                .build());
    }

    private void updateGlobalHeaderSubtitle() {
        if (active != null && active.isGlobal()) {
            chatHeader.setSubtitle("Everyone on the LAN \u00b7 " + onlineUsers.size() + " online");
        }
    }

    private boolean isSelf(String username) {
        return !currentUsername.isEmpty() && currentUsername.equals(username);
    }

    private static boolean isDirectWith(Conversation conversation, String peer) {
        return conversation != null
                && conversation.kind() == ConversationKind.DIRECT
                && conversation.peer() != null
                && conversation.peer().name().equals(peer);
    }

    /** Builds a short, single-line preview of a message for its sidebar row. */
    private static String preview(ChatMessage message) {
        if (message.isSystem()) {
            return message.content();
        }
        String body = message.content().replace('\n', ' ').strip();
        if (message.isOutgoing()) {
            body = "You: " + body;
        }
        return body.length() > PREVIEW_LENGTH ? body.substring(0, PREVIEW_LENGTH - 1).strip() + "\u2026" : body;
    }
}
