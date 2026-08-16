# LAN Messenger

A modern **JavaFX desktop LAN messenger**. Clients connect to a central TCP
server, which tracks connected users and routes messages between them. Clients
never talk to each other directly — everything goes through the server.

---

## Architecture

The project is a **multi-module Maven build** with a clean separation of concerns:

| Module   | Responsibility                                                                |
|----------|-------------------------------------------------------------------------------|
| `common` | Shared protocol/model code (`Message`, `MessageType`, `Protocol`).            |
| `server` | Multi-client TCP server: accepts connections, tracks users, routes messages.  |
| `client` | JavaFX desktop app; reusable client networking layer (`client.net`) done, chat UI next. |

```
lan-messenger/
├── pom.xml       # Parent (aggregator) POM
├── common/       # Shared protocol + model (Message, MessageType, Protocol)
├── server/       # Multi-client TCP server (ChatServer, ClientHandler, ...)
├── client/       # JavaFX desktop client (networking layer done; UI shell)
└── README.md
```

### How the server works

The server accepts each client and hands the connection to a dedicated handler
thread from a pool, so many clients are served concurrently. Connected usernames
are kept in a thread-safe registry, and every connection is fully isolated: one
client dropping — even abruptly — never disturbs the others or the server. Stopping
the process shuts the listening socket, disconnects clients, and drains the pool
gracefully.

### How the client connects

The client's networking lives in the `client.net` package, deliberately free of
any JavaFX types so it can be unit-tested headlessly. `ChatClient` is the façade
the UI will use: it opens a `ServerConnection` (a TCP socket wrapped in UTF-8 line
streams), sends the `LOGIN`, then runs a `MessageReader` on a dedicated background
thread so the blocking read loop never touches the UI. Outbound writes go through
a second background thread via `MessageSender`, so even a stalled socket cannot
freeze the interface. Incoming messages and lifecycle events are reported through
a `ChatClientListener`; because those callbacks fire off-thread, the JavaFX bridge
`FxChatClientListener` re-dispatches each one onto the JavaFX Application Thread
with `Platform.runLater`. Teardown is funnelled through one idempotent path, so a
disconnect — whether local, server-initiated, or caused by an I/O error — notifies
the listener exactly once.

### Message protocol

Every message is a single UTF-8 line with four pipe-separated fields:

```
TYPE|sender|recipient|content
```

`content` is always the last field, so it may safely contain the `|` delimiter.
The message types are `LOGIN`, `LOGIN_SUCCESS`, `LOGIN_FAILED`, `GLOBAL_MESSAGE`,
`PRIVATE_MESSAGE`, `USER_LIST`, `USER_JOINED`, `USER_LEFT`, `DISCONNECT` and
`ERROR`. New capabilities are added simply by introducing new types, keeping the
protocol easy to extend.

---

## Technology

- Java 21 (compiled with `--release 21`; runs on newer JDKs)
- JavaFX 21
- Maven (multi-module)
- TCP sockets + multithreading (Java standard library)
- JUnit 5 (tests)
- Git

---

## Prerequisites

- JDK 21 or newer
- Maven 3.9+

---

## Build

Compile and package every module from the project root:

```bash
mvn clean package
```

---

## Run

**Start the server** (listens on TCP port 5000 by default):

```bash
mvn -pl server exec:java
```

Choose a different port with:

```bash
mvn -pl server exec:java -Dexec.args="5050"
```

Press `Ctrl+C` to stop the server; it disconnects clients and shuts down
gracefully.

**Start the client** (opens the JavaFX window — a UI shell for now; the client
networking layer is in place and the chat UI that uses it arrives next):

```bash
mvn -pl client javafx:run
```

---

## Test

Run the unit and integration tests:

```bash
mvn test
```

The `server` module includes protocol unit tests and real-socket integration
tests that cover multiple clients connecting, message routing, and both clean and
abrupt disconnects. The `client` module adds integration tests that drive the
networking layer against a real server over loopback sockets, verifying connect,
send, receive, multiple concurrent clients, and safe local and server-side
disconnects.

---

## Changelog

All notable changes are recorded here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is
pre-release (`1.0-SNAPSHOT`), so current work lives under **Unreleased**.

### [Unreleased]

#### Added
- **Multi-client TCP chat server** (`server`): `ChatServer` accepts many clients
  and services each on its own pooled thread; `ClientManager` maintains a
  thread-safe registry of unique usernames; the server stops gracefully via a JVM
  shutdown hook.
- **Configurable port** (`ServerConfiguration`): `--port <n>` or the
  `lanmessenger.port` system property, defaulting to `5000`.
- **Line-based message protocol** (`common`): `Message` and `MessageType` define a
  simple, extensible wire format for login, global/private messages, user-list and
  presence notifications, and errors.
- **Client networking layer** (`client`, package `client.net`): a UI-agnostic
  `ChatClient` façade over `ServerConnection`, `MessageSender` and a background
  `MessageReader`, reporting through a `ChatClientListener`. Receiving (and
  sending) runs off the JavaFX Application Thread, and `FxChatClientListener`
  marshals callbacks back onto it via `Platform.runLater`. Supports server IP,
  port and username, with connection-error handling and safe, idempotent
  disconnects.
- **Automated tests** (JUnit 5): protocol unit tests plus real-socket integration
  tests for multi-client connect, message routing, and disconnect resilience;
  client-side integration tests exercise the networking layer against a live
  server (connect, send/receive, multiple clients, and disconnect handling).

#### Changed
- Replaced the placeholder `ServerApp` with `ServerApplication`, adding the
  shutdown hook and compact single-line logging.

### Project setup

#### Added
- Multi-module Maven build (`common`, `server`, `client`) targeting Java 21.
- Minimal JavaFX client shell and shared `Protocol` constants.
