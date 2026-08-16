package com.lanmessenger.client.ui.components;

import com.lanmessenger.client.ui.model.ChatUser;
import com.lanmessenger.client.ui.model.Conversation;
import com.lanmessenger.client.ui.model.Presence;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * A circular text avatar: coloured disc with one or two initials, and an optional
 * presence dot in the lower-right corner.
 *
 * <p>Reused by the sidebar rows, the chat header and message rows. The disc colour
 * is chosen deterministically from a seed string (usually the user's name) so a
 * given person always gets the same colour, and — crucially — the palette itself
 * lives in {@code theme.css} (the {@code .avatar-c0 … .avatar-c7} classes). This
 * class only picks which class to apply, never a raw colour.
 */
public final class Avatar extends StackPane {

    /** Rendered size; maps to the {@code .avatar-sm/.avatar-lg} CSS modifiers. */
    public enum Size { SMALL, MEDIUM, LARGE }

    private static final int PALETTE_SIZE = 8;

    private final Label initialsLabel = new Label();
    private Region presenceDot;

    private Avatar() {
        getStyleClass().add("avatar");
        setAlignment(Pos.CENTER);
        initialsLabel.getStyleClass().add("avatar-initials");
        getChildren().add(initialsLabel);
    }

    // ---- Factories -------------------------------------------------------

    /** Avatar for a user: coloured by name, showing initials and a presence dot. */
    public static Avatar forUser(ChatUser user) {
        Avatar avatar = new Avatar();
        avatar.getStyleClass().add(colorClass(user.name()));
        avatar.initialsLabel.setText(user.initials());
        avatar.setPresence(user.presence());
        return avatar;
    }

    /** Avatar for the global room: brand gradient with a "#" channel glyph. */
    public static Avatar global() {
        Avatar avatar = new Avatar();
        avatar.getStyleClass().add("avatar-global");
        avatar.initialsLabel.setText("#");
        return avatar;
    }

    /** Chooses the right avatar style for a whole conversation. */
    public static Avatar forConversation(Conversation conversation) {
        if (conversation.isGlobal() || conversation.peer() == null) {
            return global();
        }
        return forUser(conversation.peer());
    }

    // ---- Configuration ---------------------------------------------------

    /** Applies a size modifier and returns {@code this} for fluent use. */
    public Avatar size(Size size) {
        getStyleClass().removeAll("avatar-sm", "avatar-lg");
        switch (size) {
            case SMALL -> getStyleClass().add("avatar-sm");
            case LARGE -> getStyleClass().add("avatar-lg");
            default -> { /* MEDIUM is the base .avatar size */ }
        }
        return this;
    }

    /**
     * Shows (or updates) the presence dot. Passing {@code null} removes it.
     *
     * @param presence the presence to reflect, or {@code null} for none
     */
    public void setPresence(Presence presence) {
        if (presence == null) {
            if (presenceDot != null) {
                getChildren().remove(presenceDot);
                presenceDot = null;
            }
            return;
        }
        if (presenceDot == null) {
            presenceDot = new Region();
            presenceDot.setManaged(false); // overlay: don't affect avatar sizing
            getChildren().add(presenceDot);
            StackPane.setAlignment(presenceDot, Pos.BOTTOM_RIGHT);
        }
        presenceDot.getStyleClass().setAll("presence-dot", presence.styleClass());
        // Nudge the dot slightly past the disc edge once laid out.
        applyCss();
        layout();
        positionPresenceDot();
    }

    /** Removes the presence dot (used for message-row avatars where it is noise). */
    public Avatar withoutPresence() {
        setPresence(null);
        return this;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        positionPresenceDot();
    }

    private void positionPresenceDot() {
        if (presenceDot == null) {
            return;
        }
        presenceDot.autosize();
        double size = presenceDot.prefWidth(-1);
        // Sit the dot on the lower-right, overlapping the disc edge a touch.
        presenceDot.relocate(getWidth() - size + 1, getHeight() - size + 1);
    }

    /**
     * Deterministically maps a seed (typically a username) to one of the palette
     * classes defined in the stylesheet.
     *
     * @param seed the seed string
     * @return a style-class name such as {@code "avatar-c3"}
     */
    public static String colorClass(String seed) {
        int index = Math.floorMod(seed.hashCode(), PALETTE_SIZE);
        return "avatar-c" + index;
    }
}
