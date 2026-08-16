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
| `client` | JavaFX desktop app: reusable networking layer (`client.net`) plus a modern, component-based chat UI (`client.ui`). |

```
lan-messenger/
├── pom.xml       # Parent (aggregator) POM
├── common/       # Shared protocol + model (Message, MessageType, Protocol)
├── server/       # Multi-client TCP server (ChatServer, ClientHandler, ...)
├── client/       # JavaFX desktop client (networking layer + component-based UI)
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

### The client UI

The desktop UI lives in `client.ui` and is built from small, reusable components
in `client.ui.components`, composed by `MainView` into a modern messenger shell:
a title bar with a live connection `StatusIndicator`, a searchable conversation
`Sidebar`, a `ChatHeader`, a scrolling `MessageListView` (date dividers, grouped
`MessageBubble`s, an `EmptyState`, and auto-scroll) and a `MessageComposer`
(Enter-to-send, disabled while empty). Presence dots and coloured `Avatar`s round
out the look.

All colours, spacing and interactive states (hover, focus, pressed, disabled,
selected, empty) are defined once in a centralised design-token stylesheet,
`theme.css`; Java assigns style classes only and never hard-codes colours, so the
whole app can be re-skinned from one place. The layout is responsive down to the
minimum window size, with bubbles that reflow as the window resizes and animations
kept deliberately subtle.

This phase establishes the visual system only: the UI runs on in-memory sample
data (`SampleData`) via lightweight view-models (`ChatUser`, `ChatMessage`,
`Conversation`).

### Connecting and logging in

On launch the client shows a polished **connection screen** — username, server IP
and port — built from the same design system. Inputs are validated up front by the
JavaFX-free `ConnectionValidator`, which surfaces clear, specific errors (an empty
or invalid username, an invalid server IP, a port outside 1–65535). The username
rules live in `common`'s `Usernames`, shared with the server so client and server
validation never drift.

Pressing **Connect** runs the blocking socket handshake on a background task, so
the JavaFX Application Thread never freezes, and the button shows a subtle
"connecting" state. `ClientController` then drives the `LOGIN` handshake: on
`LOGIN_SUCCESS` it transitions to the messenger shell and lights up a live
**Connected** status; on `LOGIN_FAILED` (for example a username already in use) it
shows the reason cleanly and lets the user adjust and retry; and if the socket
cannot be opened it reports "Unable to connect to server." A connection that later
drops returns to the connection screen.

Wiring live message exchange (the roster and global/private messages) from the
transcript to `client.net` is the next phase — the messenger currently still shows
`SampleData` once connected.

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

Build and install every module from the project root:

```bash
mvn clean install
```

> **Use `install`, not just `package`.** The run commands below use `-pl`
> (single-module) invocations, so they resolve the shared `common` module from
> your local Maven repository (`~/.m2`). `mvn clean package` never (re)installs
> `common` there, so after any change to `common` a `-pl server`/`-pl client`
> run would link against a **stale** `common` and fail at runtime with
> `NoClassDefFoundError: com/lanmessenger/common/Message` (or `Usernames`) — for
> example the moment you press **Connect** on the login screen. Running
> `mvn clean install` (or launching from the reactor) keeps `common` in sync.

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

**Start the client** (opens the JavaFX connection screen — enter a username, the
server's IP and port, then **Connect**. On a successful login it transitions to the
messenger shell: title bar with a live connection status, sidebar, chat transcript
and composer. Start the server first, or point it at another machine's IP on your
LAN):

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
send, receive, multiple concurrent clients, safe local and server-side
disconnects, a rejected duplicate-username login, and a clean failure when the
server is unavailable. Fast, headless unit tests for `ConnectionValidator` cover
the connection-screen input rules (empty/invalid username, invalid server IP, and
out-of-range or non-numeric port).

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
- **Modern JavaFX messenger UI** (`client`, packages `client.ui` and
  `client.ui.components`): a polished, dark-themed shell composed by `MainView` —
  a title bar with a connection `StatusIndicator`, a searchable `Sidebar`
  (`SidebarItem` rows with avatars, presence dots and unread badges), a
  `ChatHeader`, a `MessageListView` (date dividers, grouped `MessageBubble`s,
  auto-scroll and an `EmptyState`) and a `MessageComposer` (Enter-to-send, disabled
  while empty). Reusable `Avatar` and `StatusIndicator` components and view-models
  (`ChatUser`, `ChatMessage`, `Conversation`) round it out. Every colour, radius and
  interactive state (hover, focus, pressed, disabled, selected, empty) is defined
  once in a centralised design-token stylesheet (`theme.css`); the layout is
  responsive with reflowing bubbles and subtle animations. The transcript runs on
  in-memory `SampleData`; the connection/login flow below is now wired to
  `client.net`, with live message exchange to follow.
- **Client connection & login flow** (`client`): the app now opens on a polished
  connection screen (`ConnectView`) collecting username, server IP and port. Input
  is validated up front by the JavaFX-free `ConnectionValidator` with clear, field
  specific errors (empty/invalid username, invalid server IP, out-of-range or non
  numeric port). `ClientController` connects on a background task — so the JavaFX
  thread never freezes — shows a subtle "connecting" state, then drives the `LOGIN`
  handshake: `LOGIN_SUCCESS` transitions to the messenger shell with a live
  **Connected** status; `LOGIN_FAILED` (e.g. a username already in use) and an
  unreachable server ("Unable to connect to server.") are reported cleanly for a
  retry, and a dropped connection returns to the connection screen.
- **Automated tests** (JUnit 5): protocol unit tests plus real-socket integration
  tests for multi-client connect, message routing, and disconnect resilience;
  client-side integration tests exercise the networking layer against a live
  server (connect, send/receive, multiple clients, disconnect handling, and a
  rejected duplicate-username login), plus headless `ConnectionValidator` unit
  tests for the connection-screen input rules.

#### Changed
- Replaced the placeholder `ServerApp` with `ServerApplication`, adding the
  shutdown hook and compact single-line logging.
- Replaced the client's placeholder launch card with the full messenger layout
  (`MainView`); `ClientApp` now loads the design system through `Theme` and, under
  `LANMSG_SMOKE=1`, runs a self-closing multi-size layout check to guard against
  size-dependent regressions.
- The client now launches into the connection screen (`ConnectView` driven by
  `ClientController`) rather than straight into `MainView`; the `LANMSG_SMOKE=1`
  layout check now exercises both screens.
- Extracted the username policy into `common`'s `Usernames`, now shared by the
  server's `ClientHandler` and the client's `ConnectionValidator` so client-side
  and server-side validation cannot drift.

### Project setup

#### Added
- Multi-module Maven build (`common`, `server`, `client`) targeting Java 21.
- Minimal JavaFX client shell and shared `Protocol` constants.
