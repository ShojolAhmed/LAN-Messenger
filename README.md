# LAN Messenger

A modern **JavaFX desktop LAN messenger**. Clients connect to a central TCP
server, which tracks connected users and routes messages between them. Clients
never talk to each other directly — everything goes through the server.

> **Status:** Phase 1 — project setup. Chat functionality is not implemented yet.

---

## Architecture

```
                ┌──────────────────┐
                │     SERVER       │
                │   TCP Port 5000  │
                └────────┬─────────┘
                         │
            ┌────────────┼────────────┐
            │            │            │
            ▼            ▼            ▼
         Client       Client       Client
         Shojol       Rahim        Karim
```

The project is a **multi-module Maven build** with a clean separation of concerns:

| Module   | Responsibility                                                        |
|----------|-----------------------------------------------------------------------|
| `common` | Shared protocol/model code used by both the client and the server.    |
| `server` | Central TCP server: accepts connections and (later) routes messages.  |
| `client` | JavaFX desktop application.                                           |

```
lan-messenger/
├── pom.xml            # Parent (aggregator) POM
├── common/            # Shared code
├── server/            # TCP server
├── client/            # JavaFX client
├── README.md
└── .gitignore
```

---

## Technology

- Java 21 (compiled with `--release 21`; runs on newer JDKs)
- JavaFX 21
- Maven (multi-module)
- TCP sockets + multithreading (Java standard library)
- Git

> SQLite/JDBC persistence will be added in a later phase, when it is actually needed.

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

## Run

**Start the server** (listens on TCP port 5000):

```bash
mvn -pl server exec:java
```

**Start the client** (opens the JavaFX window):

```bash
mvn -pl client javafx:run
```

---

## Roadmap

- [x] Phase 1 — Project setup: multi-module Maven, JavaFX, minimal server & client
- [ ] Phase 2 — Networking protocol & connection handling
- [ ] Phase 3 — User tracking & message routing
- [ ] Phase 4 — Chat UI (message bubbles, user list)
- [ ] Phase 5 — Persistence (SQLite/JDBC)
