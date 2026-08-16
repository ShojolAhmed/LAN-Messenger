package com.lanmessenger.server;

import com.lanmessenger.common.Protocol;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Minimal entry point for the LAN Messenger server.
 *
 * <p>For this first phase the server only proves that it can start and accept
 * TCP connections on {@link Protocol#DEFAULT_PORT}. Message routing, user
 * tracking and broadcasting will be added in later phases.
 */
public final class ServerApp {

    public static void main(String[] args) {
        int port = Protocol.DEFAULT_PORT;

        // try-with-resources guarantees the server socket is closed on exit.
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println(Protocol.APP_NAME + " server listening on port " + port);

            // Accept connections until the process is stopped.
            while (true) {
                Socket client = serverSocket.accept();
                System.out.println("Client connected: " + client.getRemoteSocketAddress());

                // No chat protocol yet: close the connection immediately.
                client.close();
            }
        } catch (IOException e) {
            System.err.println("Failed to start server on port " + port + ": " + e.getMessage());
        }
    }

    private ServerApp() {
        // Entry-point class: not meant to be instantiated.
    }
}
