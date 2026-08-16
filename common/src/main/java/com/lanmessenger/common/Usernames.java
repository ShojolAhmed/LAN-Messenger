package com.lanmessenger.common;

/**
 * Shared rules for what makes a valid chat username.
 *
 * <p>The username policy is enforced authoritatively by the server at login, but
 * the client validates the same rules up-front so it can give immediate feedback
 * without a network round-trip. Keeping the single definition here in
 * {@code common} is what stops the two sides from drifting apart: both reference
 * the exact same {@link #PATTERN} and {@link #MAX_LENGTH}.
 *
 * <p>A username is 1&ndash;{@value #MAX_LENGTH} characters drawn from letters,
 * digits and the punctuation {@code '.'}, {@code '_'} and {@code '-'}. This keeps
 * names safe to render, safe on the wire (no delimiter or whitespace surprises),
 * and easy to type.
 */
public final class Usernames {

    /** Maximum number of characters allowed in a username. */
    public static final int MAX_LENGTH = 24;

    /** Regular expression a username must fully match to be valid. */
    public static final String PATTERN = "[A-Za-z0-9._-]{1," + MAX_LENGTH + "}";

    private Usernames() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * Tests whether {@code username} satisfies the username policy.
     *
     * @param username the candidate name (not trimmed here; callers trim first
     *                 if they want to ignore surrounding whitespace)
     * @return {@code true} if the name matches {@link #PATTERN}; {@code false} for
     *         {@code null}, empty, too-long or otherwise disallowed names
     */
    public static boolean isValid(String username) {
        return username != null && username.matches(PATTERN);
    }
}
