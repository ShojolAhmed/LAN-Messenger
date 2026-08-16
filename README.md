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
| `client` | JavaFX desktop application (UI shell for now; networking in a later phase).   |

```
lan-messenger/
├── pom.xml       # Parent (aggregator) POM
├── common/       # Shared protocol + model (Message, MessageType, Protocol)
├── server/       # Multi-client TCP server (ChatServer, ClientHandler, ...)
├── client/       # JavaFX desktop client (UI shell for now)
└── README.md
```

### How the server works

The server accepts each client and hands the connection to a dedicated handler
thread from a pool, so many clients are served concurrently. Connected usernames
are kept in a thread-safe registry, and every connection is fully isolated: one
client dropping — even abruptly — never disturbs the others or the server. Stopping
the process shuts the listening socket, disconnects clients, and drains the pool
gracefully.

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

**Start the client** (opens the JavaFX window — a UI shell for now; chat and
networking arrive in a later phase):

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
abrupt disconnects.

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
- **Automated tests** (JUnit 5): protocol unit tests plus real-socket integration
  tests for multi-client connect, message routing, and disconnect resilience.

#### Changed
- Replaced the placeholder `ServerApp` with `ServerApplication`, adding the
  shutdown hook and compact single-line logging.

### Project setup

#### Added
- Multi-module Maven build (`common`, `server`, `client`) targeting Java 21.
- Minimal JavaFX client shell and shared `Protocol` constants.
