package com.lanmessenger.client.ui;

import javafx.scene.Scene;

import java.util.Objects;

/**
 * Central access point for the application's stylesheet.
 *
 * <p>All visual design lives in {@code theme.css}; this helper is the one place
 * that knows where that resource is and how to attach it to a {@link Scene}. Keeping
 * the path here means components never repeat the classpath string, and there is a
 * single seam to swap themes later.
 */
public final class Theme {

    /** Classpath location of the design-system stylesheet. */
    public static final String STYLESHEET = "/com/lanmessenger/client/theme.css";

    private Theme() {
        // Utility class.
    }

    /** @return the stylesheet URL in the external form JavaFX expects. */
    public static String stylesheet() {
        return Objects.requireNonNull(
                Theme.class.getResource(STYLESHEET),
                "theme.css not found on classpath at " + STYLESHEET
        ).toExternalForm();
    }

    /**
     * Attaches the design-system stylesheet to a scene.
     *
     * @param scene the scene to style
     */
    public static void apply(Scene scene) {
        scene.getStylesheets().add(stylesheet());
    }
}
