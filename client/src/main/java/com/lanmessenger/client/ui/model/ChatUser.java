package com.lanmessenger.client.ui.model;

import java.util.Objects;

/**
 * An immutable view-model for a chat participant shown in the UI (sidebar rows,
 * chat header, avatars).
 *
 * <p>This is deliberately a small, JavaFX-free value type: the UI phase uses
 * sample instances, and a later phase can map the networking layer's roster onto
 * these without any UI changes.
 *
 * @param name     the user's display name (must be non-blank)
 * @param presence the user's current availability
 */
public record ChatUser(String name, Presence presence) {

    public ChatUser {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(presence, "presence");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** Convenience constructor defaulting to {@link Presence#ONLINE}. */
    public ChatUser(String name) {
        this(name, Presence.ONLINE);
    }

    /**
     * Derives up to two uppercase initials for use in a text avatar. Uses the
     * first letters of the first two whitespace-separated words, falling back to
     * the first character for single-word names.
     *
     * @return one or two uppercase letters (never empty for a valid name)
     */
    public String initials() {
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase();
        }
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
    }
}
