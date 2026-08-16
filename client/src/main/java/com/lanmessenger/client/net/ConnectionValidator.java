package com.lanmessenger.client.net;

import com.lanmessenger.common.Usernames;

import java.util.regex.Pattern;

/**
 * Validates the three connection inputs&mdash;username, server IP/host and
 * port&mdash;before any socket is opened, so the UI can report a clear, specific
 * error the instant the user submits the form.
 *
 * <p>This class is deliberately free of any JavaFX types: like the rest of
 * {@code client.net} it can be unit-tested headlessly. The username rules are not
 * duplicated here&mdash;they defer to {@link Usernames} in the {@code common}
 * module, the same definition the server enforces at login&mdash;so client-side
 * pre-validation can never drift from the server's policy.
 *
 * <p>Validation stops at the first problem and reports it via {@link Result},
 * which also carries the offending {@link Field} so the UI can highlight the right
 * input. On success the {@code Result} exposes the trimmed, parsed values ready to
 * hand to {@link ChatClient#connect(String, int, String)}.
 */
public final class ConnectionValidator {

    /** Which input a validation error refers to (for focusing/highlighting). */
    public enum Field {
        NONE, USERNAME, HOST, PORT
    }

    /** Lowest and highest usable TCP port. */
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /** A dotted, all-numeric string is treated strictly as an IPv4 address. */
    private static final Pattern DOTTED_NUMERIC = Pattern.compile("[0-9.]+");
    private static final Pattern IPV4 =
            Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    /** A conservative RFC-1123 host name (labels of letters/digits/hyphens). */
    private static final Pattern HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

    private ConnectionValidator() {
        // Utility class: not meant to be instantiated.
    }

    /**
     * The outcome of validating a set of connection inputs. When {@link #valid()}
     * is {@code true} the {@code username}/{@code host}/{@code port} accessors hold
     * the cleaned values; otherwise {@link #error()} and {@link #field()} describe
     * the first problem found.
     */
    public record Result(boolean valid, String error, Field field,
                         String username, String host, int port) {

        static Result ok(String username, String host, int port) {
            return new Result(true, null, Field.NONE, username, host, port);
        }

        static Result failure(String error, Field field) {
            return new Result(false, error, field, null, null, 0);
        }
    }

    /**
     * Validates the raw form inputs.
     *
     * @param usernameRaw the username as typed (may be {@code null})
     * @param hostRaw     the server IP/host as typed (may be {@code null})
     * @param portRaw     the port as typed (may be {@code null})
     * @return a {@link Result} describing success (with cleaned values) or the
     *         first validation error and the field it applies to
     */
    public static Result validate(String usernameRaw, String hostRaw, String portRaw) {
        String username = trim(usernameRaw);
        if (username.isEmpty()) {
            return Result.failure("Username cannot be empty.", Field.USERNAME);
        }
        if (username.length() > Usernames.MAX_LENGTH) {
            return Result.failure("Username is too long (max " + Usernames.MAX_LENGTH + " characters).",
                    Field.USERNAME);
        }
        if (!Usernames.isValid(username)) {
            return Result.failure("Username contains invalid characters.", Field.USERNAME);
        }

        String host = trim(hostRaw);
        if (!isValidHost(host)) {
            return Result.failure("Server IP is invalid.", Field.HOST);
        }

        Integer port = parsePort(trim(portRaw));
        if (port == null || port < MIN_PORT || port > MAX_PORT) {
            return Result.failure("Port must be between " + MIN_PORT + " and " + MAX_PORT + ".", Field.PORT);
        }

        return Result.ok(username, host, port);
    }

    /**
     * @return {@code true} if {@code host} is a syntactically valid IPv4 address or
     *         host name. A dotted, all-numeric value must be a well-formed IPv4
     *         address (so {@code "1.2.3"} and {@code "999.1.1.1"} are rejected),
     *         while other values are validated as host names (so {@code "localhost"}
     *         is accepted).
     */
    public static boolean isValidHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (DOTTED_NUMERIC.matcher(host).matches()) {
            return isIpv4(host);
        }
        return HOSTNAME.matcher(host).matches();
    }

    private static boolean isIpv4(String host) {
        var matcher = IPV4.matcher(host);
        if (!matcher.matches()) {
            return false;
        }
        for (int group = 1; group <= 4; group++) {
            if (Integer.parseInt(matcher.group(group)) > 255) {
                return false;
            }
        }
        return true;
    }

    private static Integer parsePort(String portText) {
        if (portText.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
