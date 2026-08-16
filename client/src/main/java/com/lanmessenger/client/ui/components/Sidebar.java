package com.lanmessenger.client.ui.components;

import com.lanmessenger.client.ui.model.Conversation;
import com.lanmessenger.client.ui.model.ConversationKind;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The left navigation rail: a header with a title and search box, a section label,
 * and a scrollable list of {@link SidebarItem} rows (the global room followed by
 * direct chats).
 *
 * <p>The sidebar owns single-selection state and reports the chosen conversation
 * through {@link #setOnConversationSelected(Consumer)}. The search field filters
 * rows live by title — a small, self-contained interaction that showcases the
 * input's focus state without any networking.
 */
public final class Sidebar extends VBox {

    private final VBox list = new VBox();
    private final List<SidebarItem> items = new ArrayList<>();

    private Consumer<Conversation> onConversationSelected = conversation -> { };
    private SidebarItem selected;

    /**
     * @param conversations the conversations to list, in display order (the first
     *                      one is treated as the global room)
     */
    public Sidebar(List<Conversation> conversations) {
        getStyleClass().add("sidebar");

        // ---- Header: title + search ----
        Label title = new Label("Chats");
        title.getStyleClass().add("sidebar-title");

        TextField search = new TextField();
        search.getStyleClass().add("search-field");
        search.setPromptText("Search conversations");
        search.textProperty().addListener((obs, old, query) -> filter(query));

        VBox header = new VBox(title, search);
        header.getStyleClass().add("sidebar-header");

        // ---- Rows ----
        list.getStyleClass().add("sidebar-list");
        boolean sectionAdded = false;
        for (Conversation conversation : conversations) {
            // Insert a section label before the first direct message.
            if (!sectionAdded && conversation.kind() == ConversationKind.DIRECT) {
                Label section = new Label("DIRECT MESSAGES");
                section.getStyleClass().add("sidebar-section");
                list.getChildren().add(section);
                sectionAdded = true;
            }
            SidebarItem item = new SidebarItem(conversation, this::select);
            items.add(item);
            list.getChildren().add(item);
        }

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

    /** Selects the first row (the global room), if any. */
    public void selectFirst() {
        if (!items.isEmpty()) {
            select(items.get(0));
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

    /** Shows only rows whose title contains {@code query} (case-insensitive). */
    private void filter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (SidebarItem item : items) {
            boolean match = needle.isEmpty()
                    || item.conversation().title().toLowerCase(Locale.ROOT).contains(needle);
            item.setVisible(match);
            item.setManaged(match);
        }
    }
}
