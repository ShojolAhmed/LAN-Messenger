package com.lanmessenger.client;

/**
 * Thin launcher used <b>only</b> by the packaged (jpackage) Windows distribution.
 *
 * <p>When JavaFX is provided on the <em>class path</em> (as it is in the packaged
 * app) and the JVM's main class is a subclass of
 * {@link javafx.application.Application}, the Java launcher's built-in JavaFX
 * check fails with <em>"JavaFX runtime components are missing, and are required to
 * run the application"</em>. Using a main class that does <b>not</b> extend
 * {@code Application} sidesteps that check; this launcher simply delegates to the
 * real entry point, so the application's behaviour is completely unchanged.
 *
 * <p>The normal development run (<code>mvn -pl client javafx:run</code>) still uses
 * {@link ClientApp} directly and does not go through this class.
 */
public final class Launcher {

    public static void main(String[] args) {
        ClientApp.main(args);
    }

    private Launcher() {
        // Not meant to be instantiated.
    }
}
