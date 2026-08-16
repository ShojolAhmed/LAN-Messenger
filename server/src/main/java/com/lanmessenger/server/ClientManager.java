package com.lanmessenger.server;

import com.lanmessenger.common.Message;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The server's thread-safe registry of logged-in clients.
 *
 * <p>Many {@link ClientHandler} threads read from and write to this registry at
 * the same time (one thread per connected client), so the backing store is a
 * {@link ConcurrentHashMap}. That single choice gives us:
 * <ul>
 *   <li>atomic, race-free username reservation via
 *       {@link ConcurrentHashMap#putIfAbsent(Object, Object)}, and</li>
 *   <li>a weakly-consistent iterator, so broadcasting never throws
 *       {@code ConcurrentModificationException} even while users join and leave.</li>
 * </ul>
 *
 * <p>Usernames are the map keys, which is what lets the server "associate a
 * username with a connection" and keep those associations unique.
 */
public final class ClientManager {

    private static final Logger LOG = Logger.getLogger(ClientManager.class.getName());

    /** username &rarr; handler. */
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();

    /**
     * Atomically reserves {@code username} for {@code handler}.
     *
     * @return {@code true} if the name was free and is now registered;
     *         {@code false} if it was already taken
     */
    public boolean register(String username, ClientHandler handler) {
        boolean added = clients.putIfAbsent(username, handler) == null;
        if (added) {
            LOG.info(() -> "Registered user '" + username + "' (" + clients.size() + " online)");
        }
        return added;
    }

    /**
     * Removes a username, but only if it currently maps to {@code handler}. This
     * guards against a re-connected user with the same name being evicted by a
     * late cleanup from an older, dead connection.
     */
    public void remove(String username, ClientHandler handler) {
        if (username != null && clients.remove(username, handler)) {
            LOG.info(() -> "Removed user '" + username + "' (" + clients.size() + " online)");
        }
    }

    /** @return {@code true} if {@code username} is currently taken. */
    public boolean isRegistered(String username) {
        return clients.containsKey(username);
    }

    /** @return the number of logged-in clients. */
    public int size() {
        return clients.size();
    }

    /** @return an alphabetically sorted snapshot of the online usernames. */
    public List<String> usernames() {
        return List.copyOf(new TreeSet<>(clients.keySet()));
    }

    /** Sends {@code message} to every logged-in client. */
    public void broadcast(Message message) {
        broadcast(message, null);
    }

    /**
     * Sends {@code message} to every logged-in client except {@code excludeUsername}
     * (pass {@code null} to exclude nobody). Typically used so a sender does not
     * receive an echo of their own broadcast.
     */
    public void broadcast(Message message, String excludeUsername) {
        for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
            if (!entry.getKey().equals(excludeUsername)) {
                entry.getValue().send(message);
            }
        }
    }

    /**
     * Sends {@code message} to a single named user.
     *
     * @return {@code true} if the user was online and the message was handed to
     *         their connection; {@code false} if no such user exists
     */
    public boolean sendToUser(String username, Message message) {
        ClientHandler handler = clients.get(username);
        if (handler == null) {
            return false;
        }
        handler.send(message);
        return true;
    }

    /**
     * Closes every client connection. Called during server shutdown; each handler
     * is closed defensively so one failure cannot stop the rest from closing.
     */
    public void disconnectAll() {
        for (ClientHandler handler : clients.values()) {
            try {
                handler.close();
            } catch (RuntimeException ex) {
                LOG.warning(() -> "Error while closing a client during shutdown: " + ex.getMessage());
            }
        }
        clients.clear();
    }
}
