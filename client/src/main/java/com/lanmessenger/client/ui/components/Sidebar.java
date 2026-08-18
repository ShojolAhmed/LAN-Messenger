package com.lanmessenger.client.ui.components;

import com.lanmessenger.client.ui.model.Conversation;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The left rail: a searchable list of conversations. The pinned <b>Global</b> room
 * sits at the top, followed by an <b>ONLINE</b> section that lists every other
 * connected user (each row opens a one-to-one private conversation).
 *
 * <p>Unlike a static list, the sidebar is <b>live</b>. As people join and leave,
 * {@link #setOnlinePeers(List)} adds and removes rows in place — reusing existing
 * {@link SidebarItem}s so their selection and unread state survive a roster change
 * — and keeps the online count current. {@link #refreshUnread(Conversation)} and
 * {@link #setPreview(Conversation, String)} update a single row without rebuilding,
 * and {@link #select(Conversation)} drives the active-conversation highlight (and
 * notifies {@link #setOnConversationSelected(Consumer) the selection callback}).
 *
 * <p>The current user is never listed here: you cannot open a private conversation
 * with yourself, so {@link com.lanmessenger.client.ui.MainView} excludes your own
 * name from the peers it supplies.
 */
public final class Sidebar extends VBox {

    private final VBox list = new VBox();
    private final TextField search = new TextField();

    private final Conversation globalConversation;
    private final SidebarItem globalItem;
    private final Label onlineSection = new Label();
    private final Label emptyHint = new Label("No one else is online yet.");

    /** Live peer rows, keyed by conversation id, in display (alphabetical) order. */
    private final Map<String, SidebarItem> peerItems = new LinkedHashMap<>();

    private Consumer<Conversation> onConversationSelected = conversation -> { };
    private SidebarItem selected;
    private String query = "";

    /**
     * @param global the pinned global conversation shown first (never removed)
     */
    public Sidebar(Conversation global) {
        this.globalConversation = global;
        getStyleClass().add("sidebar");

        // ---- Header: title + search ----
        Label title = new Label("Chats");
        title.getStyleClass().add("sidebar-title");

        search.getStyleClass().add("search-field");
        search.setPromptText("Search conversations");
        search.textProperty().addListener((obs, old, text) -> filter(text));

        VBox header = new VBox(title, search);
        header.getStyleClass().add("sidebar-header");

        // ---- Rows ----
        list.getStyleClass().add("sidebar-list");

        globalItem = new SidebarItem(global, this::select);

        onlineSection.getStyleClass().add("sidebar-section");
        emptyHint.getStyleClass().add("sidebar-empty");
        emptyHint.setWrapText(true);

        rebuildList(List.of()); // start with just Global + an empty ONLINE section

        ScrollPane scroll = new ScrollPane(list);
        scroll.getStyleClass().add("sidebar-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
    }

    /** Registers the callback fired whenever the selected conversation changes. */
    public void setOnConversationSelected(Consumer<Conversation> callback) {
        this.onConversationSelected = callback == null ? conversation -> { } : callback;
    }

    // ---------------------------------------------------------------------
    // Roster
    // ---------------------------------------------------------------------

    /**
     * Syncs the ONLINE section to {@code peers} (the direct-message conversations
     * for every currently online user, excluding yourself, in display order).
     *
     * <p>Rows for peers who left are removed; rows for new peers are added; existing
     * rows are reused so their unread badge and any selection are preserved. The
     * online count and the active search filter are refreshed.
     *
     * @param peers the online peers' conversations, already ordered for display
     */
    public void setOnlinePeers(List<Conversation> peers) {
        Set<String> present = new HashSet<>();
        for (Conversation c : peers) {
            present.add(c.id());
        }

        // Drop rows for peers who are no longer online, keeping existing rows.
        peerItems.keySet().removeIf(id -> !present.contains(id));
        if (selected != null && selected != globalItem && !peerItems.containsValue(selected)) {
            selected = null; // the selected row just left; MainView re-selects Global
        }

        // Ensure a (reused or new) row exists for each online peer, in order.
        for (Conversation c : peers) {
            peerItems.computeIfAbsent(c.id(), id -> new SidebarItem(c, this::select));
        }

        onlineSection.setText("ONLINE \u2014 " + peers.size());
        rebuildList(peers);
        filter(query);
    }

    /** Re-reads a conversation's unread count and updates its badge, if it has a row. */
    public void refreshUnread(Conversation conversation) {
        SidebarItem item = itemFor(conversation);
        if (item != null) {
            item.refreshUnread();
        }
    }

    /** Updates the secondary preview line for a conversation's row, if it has one. */
    public void setPreview(Conversation conversation, String preview) {
        SidebarItem item = itemFor(conversation);
        if (item != null) {
            item.setSubtitle(preview);
        }
    }

    // ---------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------

    /** Selects the pinned Global conversation (and fires the selection callback). */
    public void selectGlobal() {
        select(globalItem);
    }

    /**
     * Programmatically selects {@code conversation} if it has a row. No-op if the
     * conversation is not currently shown (e.g. an offline peer).
     */
    public void select(Conversation conversation) {
        SidebarItem item = itemFor(conversation);
        if (item != null) {
            select(item);
        }
    }

    private void select(SidebarItem item) {
        if (selected == item) {
            return;
        }
        if (selected != null) {
            selected.setSelected(false);
        }
        selected = item;
        selected.setSelected(true);
        onConversationSelected.accept(item.conversation());
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private SidebarItem itemFor(Conversation conversation) {
        if (conversation == null) {
            return null;
        }
        if (conversation.id().equals(globalConversation.id())) {
            return globalItem;
        }
        return peerItems.get(conversation.id());
    }

    /** Rebuilds the row order: Global, the ONLINE header, then each peer (or a hint). */
    private void rebuildList(List<Conversation> orderedPeers) {
        list.getChildren().setAll(globalItem, onlineSection);
        if (orderedPeers.isEmpty()) {
            list.getChildren().add(emptyHint);
        } else {
            for (Conversation c : orderedPeers) {
                SidebarItem item = peerItems.get(c.id());
                if (item != null) {
                    list.getChildren().add(item);
                }
            }
        }
    }

    private void filter(String text) {
        query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        applyFilter(globalItem);
        for (SidebarItem item : peerItems.values()) {
            applyFilter(item);
        }
    }

    private void applyFilter(SidebarItem item) {
        boolean match = query.isEmpty()
                || item.conversation().title().toLowerCase(Locale.ROOT).contains(query);
        item.setVisible(match);
        item.setManaged(match);
    }
}
