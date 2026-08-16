package com.lanmessenger.client.ui.model;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.Objects;

/**
 * A conversation shown in the sidebar and opened in the chat pane. This is the
 * primary view-model that ties the reusable components together: the sidebar
 * renders it as a row, the chat header renders its title/subtitle/avatar, and the
 * message list binds to its {@link #messages()}.
 *
 * <p>Unlike the other, fully-immutable view-models, a conversation owns a live
 * {@link ObservableList} of messages so the UI can append to it (for example when
 * the composer sends) and the list view updates automatically. No networking is
 * involved in this phase — appends are local, sample-data operations.
 */
public final class Conversation {

    private final String id;
    private final ConversationKind kind;
    private final String title;
    private final String headerSubtitle;
    private final String sidebarSubtitle;
    private final ChatUser peer; // null for GLOBAL
    private final ObservableList<ChatMessage> messages;
    private int unread;

    private Conversation(Builder b) {
        this.id = b.id;
        this.kind = b.kind;
        this.title = b.title;
        this.headerSubtitle = b.headerSubtitle;
        this.sidebarSubtitle = b.sidebarSubtitle;
        this.peer = b.peer;
        this.unread = b.unread;
        this.messages = FXCollections.observableArrayList(b.messages);
    }

    // ---- Accessors -------------------------------------------------------

    public String id() {
        return id;
    }

    public ConversationKind kind() {
        return kind;
    }

    public String title() {
        return title;
    }

    /** Subtitle shown under the title in the chat header. */
    public String headerSubtitle() {
        return headerSubtitle;
    }

    /** Secondary line shown under the title in the sidebar row. */
    public String sidebarSubtitle() {
        return sidebarSubtitle;
    }

    /** The other participant for a {@link ConversationKind#DIRECT} chat, else {@code null}. */
    public ChatUser peer() {
        return peer;
    }

    /** Live, observable message list; append to update the open view. */
    public ObservableList<ChatMessage> messages() {
        return messages;
    }

    /** Number of unread messages (drives the sidebar badge; {@code 0} hides it). */
    public int unread() {
        return unread;
    }

    public void setUnread(int unread) {
        this.unread = Math.max(0, unread);
    }

    public boolean isGlobal() {
        return kind == ConversationKind.GLOBAL;
    }

    /** Seed used to pick a deterministic avatar colour. */
    public String avatarSeed() {
        return peer != null ? peer.name() : title;
    }

    // ---- Builder ---------------------------------------------------------

    public static Builder builder(String id, ConversationKind kind, String title) {
        return new Builder(id, kind, title);
    }

    /** Fluent builder; keeps the several optional fields readable at call sites. */
    public static final class Builder {
        private final String id;
        private final ConversationKind kind;
        private final String title;
        private String headerSubtitle = "";
        private String sidebarSubtitle = "";
        private ChatUser peer;
        private int unread;
        private List<ChatMessage> messages = List.of();

        private Builder(String id, ConversationKind kind, String title) {
            this.id = Objects.requireNonNull(id, "id");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.title = Objects.requireNonNull(title, "title");
        }

        public Builder headerSubtitle(String value) {
            this.headerSubtitle = value == null ? "" : value;
            return this;
        }

        public Builder sidebarSubtitle(String value) {
            this.sidebarSubtitle = value == null ? "" : value;
            return this;
        }

        public Builder peer(ChatUser value) {
            this.peer = value;
            return this;
        }

        public Builder unread(int value) {
            this.unread = Math.max(0, value);
            return this;
        }

        public Builder messages(List<ChatMessage> value) {
            this.messages = value == null ? List.of() : value;
            return this;
        }

        public Conversation build() {
            return new Conversation(this);
        }
    }
}
