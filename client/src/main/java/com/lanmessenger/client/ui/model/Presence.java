package com.lanmessenger.client.ui.model;

/**
 * A user's availability, used to colour presence dots and label rows.
 *
 * <p>Each constant carries a short {@link #label()} for display and the CSS
 * style-class suffix ({@link #styleClass()}) that the {@code theme.css}
 * presence rules key off — so the colour for each state lives in CSS, never in
 * Java.
 */
public enum Presence {

    ONLINE("Online", "presence-online"),
    AWAY("Away", "presence-away"),
    OFFLINE("Offline", "presence-offline");

    private final String label;
    private final String styleClass;

    Presence(String label, String styleClass) {
        this.label = label;
        this.styleClass = styleClass;
    }

    /** @return a short, human-readable label (e.g. {@code "Online"}). */
    public String label() {
        return label;
    }

    /** @return the CSS style class that colours a {@code .presence-dot}. */
    public String styleClass() {
        return styleClass;
    }
}
