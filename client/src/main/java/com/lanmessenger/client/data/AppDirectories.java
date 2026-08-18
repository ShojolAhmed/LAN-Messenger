package com.lanmessenger.client.data;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves the local, per-user directory where the client keeps its data (the
 * SQLite chat database). The location follows each platform's convention so the
 * database lives outside the project tree and is never at risk of being committed:
 *
 * <ul>
 *   <li><b>Windows</b> &mdash; {@code %LOCALAPPDATA%\LanMessenger}
 *       (falling back to {@code %APPDATA%}, then {@code ~/AppData/Local});</li>
 *   <li><b>macOS</b> &mdash; {@code ~/Library/Application Support/LanMessenger};</li>
 *   <li><b>Linux/other</b> &mdash; {@code $XDG_DATA_HOME/lan-messenger}
 *       (falling back to {@code ~/.local/share/lan-messenger}).</li>
 * </ul>
 *
 * <p>The system property {@code lanmessenger.data.dir} overrides the directory
 * entirely, which is convenient for tests (a temporary folder) or for running a
 * portable install from a project data directory.
 *
 * <p>This class only computes paths; it never touches the filesystem. Creating the
 * directory is left to {@link Database#open(Path)}.
 */
public final class AppDirectories {

    private static final String DATA_DIR_PROPERTY = "lanmessenger.data.dir";
    private static final String WINDOWS_APP_DIR = "LanMessenger";
    private static final String UNIX_APP_DIR = "lan-messenger";
    private static final String DATABASE_FILE_NAME = "chat-history.db";

    private AppDirectories() {
    }

    /** @return the absolute path to the SQLite chat-history database file. */
    public static Path chatDatabaseFile() {
        return dataDirectory().resolve(DATABASE_FILE_NAME);
    }

    /** @return the directory that holds the client's local data. */
    public static Path dataDirectory() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String base = firstNonBlank(System.getenv("LOCALAPPDATA"), System.getenv("APPDATA"));
            Path root = base != null ? Path.of(base) : Path.of(home, "AppData", "Local");
            return root.resolve(WINDOWS_APP_DIR);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Application Support", WINDOWS_APP_DIR);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path root = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(home, ".local", "share");
        return root.resolve(UNIX_APP_DIR);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
