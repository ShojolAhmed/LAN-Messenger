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

Lightweight, JavaFX-free view-models (`ChatUser`, `ChatMessage`, `Conversation`)
bridge the wire protocol and these components, so the transcript is fed by live
network traffic (see **Global chat** below) rather than fixtures.

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

On a successful login the messenger opens straight into the shared **Global chat**
(below), and every other connected user appears in the sidebar for **one-to-one
private messaging**.

### Global chat

Once logged in, every user shares a single **global room**. The composer sends each
message to the server as a `GLOBAL_MESSAGE`; the server validates it (dropping blank
messages and truncating anything longer than `Protocol.MAX_MESSAGE_LENGTH`), stamps
it with the authenticated sender so nobody can spoof another user, and broadcasts it
to every *other* connected client. The sender shows their own message immediately,
so the room stays in sync without the server echoing messages back.

Each message is drawn as a bubble: your own are right-aligned in the accent colour,
everyone else's are left-aligned with the author's name, and both carry a timestamp.
The transcript auto-scrolls to the newest message. The composer sends on **Enter**,
keeps **Shift+Enter** for a new line, disables **Send** while empty, and caps the
message length. Join and leave notices appear as quiet system lines and the header
shows a live online count, all driven by the server's
`USER_JOINED`/`USER_LEFT`/`USER_LIST` events. A dropped connection returns to the
connection screen.

### Online users and private messaging

The server tracks every connected username and keeps all clients in step: as people
join and leave it broadcasts `USER_JOINED`/`USER_LEFT` and answers `USER_LIST`
requests. The client turns those events into a live **ONLINE** list in the sidebar —
one row per other connected user, each with a green presence dot and a running
count. You never appear as your own recipient.

Clicking a user opens a **private conversation** with them; the sidebar also keeps
the pinned **Global** room, and selecting any row switches the main chat panel.
Each conversation has its **own history in memory**, so global and private threads —
and different people's private threads — never mix. Sending routes to the active
conversation: a global message is broadcast, while a private message is sent as a
`PRIVATE_MESSAGE` addressed to a single recipient. The server looks up that
recipient's connection and forwards the note **only** to them (never to everyone);
the sender echoes its own copy locally, exactly as global chat does. A message that
arrives in a conversation you are not currently viewing raises an **unread badge**
on its sidebar row, cleared when you open it, and the active conversation is
highlighted.

Edge cases are handled end to end: a message to yourself or to someone who is not
(or no longer) online is refused by the server and reported back to the sender —
tagged with the intended recipient so the client can show a quiet "couldn't deliver"
notice in the right conversation. When a peer you are chatting with goes offline
their row is removed and the view falls back to Global, while their conversation and
its history are kept in case they return. Private content is validated just like
global chat (blank messages dropped, over-long ones truncated), and duplicate
usernames are already prevented at login, so the online list stays unique.

### Local chat history (SQLite persistence)

Conversations are persisted locally so they survive restarts. Each delivered
message — global or private, incoming or outgoing — is written to an **SQLite**
database via **JDBC**; when a conversation is opened its stored messages are loaded
back and shown in the chat panel. System notices (joins, leaves, "beginning of
conversation", delivery failures) are treated as ephemeral and are never stored.

The persistence code is kept strictly separate from the UI, networking and server
logic in two small layers under the client:

| Package             | Responsibility                                                                                             |
|---------------------|------------------------------------------------------------------------------------------------------------|
| `client.data`       | Pure JDBC: `Database` (connection + schema), `MessageDao`/`UserDao`, the `StoredMessage` row model, and `AppDirectories`. No JavaFX, no networking. |
| `client.history`    | Bridges the data layer to the UI: `ChatHistoryStore` maps rows to/from the UI's `ChatMessage`; `ChatHistory` (with `PersistentChatHistory`) runs every query on a single background thread and delivers results back on the JavaFX thread. |

Two tables back it. `messages` holds `id`, `owner` (the logged-in account, so
several users on one machine keep separate private histories), `sender`,
`recipient` (empty for global), `content`, `type` (`GLOBAL_MESSAGE` or
`PRIVATE_MESSAGE`) and a `timestamp`; a one-to-one thread is simply every private
message exchanged in either direction between the owner and a peer. `users` is a
small registry of every username seen, with first/last-seen times.

**The UI thread is never blocked**: `MainView` loads a conversation's history in
the background and merges it in when it arrives, and records new messages
fire-and-forget. **Storage failures are non-fatal**: if the database cannot be
opened the client logs it once and runs with persistence disabled (an empty
history) rather than crashing. The database lives in the per-user application data
directory (e.g. `%LOCALAPPDATA%\LanMessenger` on Windows,
`~/.local/share/lan-messenger` on Linux, `~/Library/Application Support/LanMessenger`
on macOS), so it is never committed; set `-Dlanmessenger.data.dir=<dir>` to override
the location.

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
- SQLite via JDBC (`org.xerial:sqlite-jdbc`) for local chat history
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
tests that cover multiple clients connecting, message routing (global broadcast and
one-to-one private delivery), a private message to an unknown user or to yourself
being reported with a recipient-tagged delivery error, and both clean and abrupt
disconnects. The `client` module adds integration tests that drive the networking
layer against a real server over loopback sockets, verifying connect, send, receive,
multiple concurrent clients, global delivery to every peer, a private message that
reaches only its recipient (and never a third client), a tagged delivery error for
an offline recipient, safe local and server-side disconnects, a rejected
duplicate-username login, and a clean failure when the server is unavailable. Fast,
headless unit tests for `ConnectionValidator` cover the connection-screen input
rules (empty/invalid username, invalid server IP, and out-of-range or non-numeric
port). A headless persistence suite covers the SQLite data layer (storing and
reading global and per-peer private messages, the users registry, and generated
ids), history surviving a store reopen (a "restart"), per-owner scoping, and the
graceful no-op fallback when the database cannot be opened.

---

## Changelog

All notable changes are recorded here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is
pre-release (`1.0-SNAPSHOT`), so current work lives under **Unreleased**.

### [Unreleased]

#### Added
- **Local chat history via SQLite/JDBC** (`client`): conversations now persist
  across restarts. A clean, self-contained persistence layer lives under the client
  in two packages kept separate from the UI, networking and server: `client.data`
  (pure JDBC — `Database` for the connection and schema, `MessageDao`/`UserDao`, the
  `StoredMessage` row model and `AppDirectories`) and `client.history` (the
  `ChatHistoryStore` mapping to/from the UI's `ChatMessage`, exposed to the UI as
  `ChatHistory`/`PersistentChatHistory`). Every delivered global and private message
  is recorded, and opening a conversation loads its stored messages back into the
  chat panel; system notices are not persisted. All database work runs on a single
  background thread and results are marshalled back to the JavaFX thread, so the UI
  never blocks. If the database cannot be opened the client degrades gracefully to an
  empty, in-memory-only history instead of crashing. The `messages` table stores
  `id`, `owner`, `sender`, `recipient`, `content`, `type` and `timestamp`; a `users`
  table records every username seen. The database is created in the per-user
  application data directory (overridable with `-Dlanmessenger.data.dir`) and is
  git-ignored. Added headless tests for the data layer, for history surviving a
  reopen (restart), owner scoping, and for graceful behaviour when the database
  cannot be opened.
- **Online users & one-to-one private messaging** (`client`, `server`, `common`):
  the sidebar now shows a live **ONLINE** list — one row per other connected user,
  with a presence dot and a running count — driven by the server's
  `USER_JOINED`/`USER_LEFT`/`USER_LIST` events; you never appear as your own
  recipient. Clicking a user opens a **private conversation** alongside the pinned
  **Global** room, and selecting a row switches the main panel. Each conversation
  keeps its **own in-memory history**, so global and private threads never mix. A
  private message is sent as a `PRIVATE_MESSAGE` and forwarded by the server to
  **only** that recipient (the sender echoes its own copy locally); a message that
  lands in a conversation you are not viewing raises an **unread badge**, and the
  active conversation is highlighted. Edge cases are handled end to end: the server
  drops blank private messages, truncates over-long ones, refuses a message to
  yourself or to an offline/unknown user, and reports the failure back **tagged with
  the intended recipient** (via `Message.deliveryError`) so the client shows a quiet
  "couldn't deliver" notice in the right conversation; when a peer goes offline their
  row is removed and the view falls back to Global while their history is retained.
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
  responsive with reflowing bubbles and subtle animations. (The transcript was
  initially driven by in-memory `SampleData`; it is now fed by live global chat —
  see below.)
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
- **Live global chat** (`client`): the messenger is now wired to `client.net`, so
  connected users chat in a shared room in real time. A sent message goes to the
  server and is echoed into the transcript locally; incoming `GLOBAL_MESSAGE`s
  render as bubbles that tell your own (right-aligned, accent) from others'
  (left-aligned, with the author's name), each with a timestamp, and the list
  auto-scrolls to the newest. The composer sends on **Enter**, keeps
  **Shift+Enter** for a new line, disables **Send** while empty, and caps the
  length. A live online count and join/leave notices are driven by the server's
  `USER_LIST`, `USER_JOINED` and `USER_LEFT` events.
- **Automated tests** (JUnit 5): protocol unit tests (including multi-line
  round-trips) plus real-socket integration tests for multi-client connect, message
  routing, and disconnect resilience; client-side integration tests exercise the
  networking layer against a live server (connect, send/receive, multiple clients,
  global delivery to every peer, dropped-empty and truncated over-long messages,
  disconnect handling, and a rejected duplicate-username login), plus headless
  `ConnectionValidator` unit tests for the connection-screen input rules.

#### Changed
- **Reliability & UI polish pass** (`client`, `server`): a round of hardening and
  refinement with no new features. **Reliability:** client sockets on both ends now
  enable `TCP_NODELAY` (prompt small-message delivery) and `SO_KEEPALIVE` (so a peer
  that vanishes without a clean close is eventually detected); the server's
  per-client handler now also catches unexpected `RuntimeException`s, logging them
  and cleaning up so one client can never destabilise the server or dump a raw stack
  trace; the client now shuts down cleanly when its window closes
  (`ClientApp.stop()` → `ClientController.shutdown()` disconnects the socket and
  closes the SQLite store), and installs an uncaught-exception logger so nothing
  fails silently. **UI/performance:** the transcript now stays pinned to the newest
  message only when you are already at the bottom — scrolling up to read history is
  no longer interrupted by incoming messages, while your own sent messages still
  scroll into view — and redundant per-row scroll requests are coalesced into one
  per update (notably when a conversation's history loads). **Accessibility:** the
  connection status pill, sidebar rows, message input and connect-screen fields now
  expose accessible text/roles for screen readers, and sidebar rows announce their
  unread count.
- **`MainView` now hosts multiple conversations** (the Global room plus a
  direct-message conversation per online peer) instead of a single global room, and
  takes a private-send callback alongside the global one; `ClientController` now also
  routes inbound `PRIVATE_MESSAGE`s and recipient-tagged delivery errors into it.
- **The `Sidebar` is now dynamic** rather than a fixed list built once: it adds and
  removes online-peer rows in place (reusing rows so selection and unread state
  survive a roster change), keeps a live online count, and highlights the active
  conversation. `SidebarItem` gained live unread-badge and subtitle (latest-message
  preview) updates, and `MessageComposer` gained `setPrompt` to name the active
  conversation.
- **`MainView` is now network-driven** instead of running on `SampleData` (which
  has been removed): `ClientController` builds a fresh messenger per login and
  routes inbound `GLOBAL_MESSAGE`, `USER_JOINED`, `USER_LEFT` and `USER_LIST`
  events into it.
- **`MessageComposer` is now a multi-line `TextArea`**: Enter sends, Shift+Enter
  inserts a newline, and typing is capped at `Protocol.MAX_MESSAGE_LENGTH`. Its
  styling was updated in `theme.css` to blend into the composer pill.
- **The wire protocol preserves newlines** in message content (encoded as a
  separator that `BufferedReader.readLine()` ignores) instead of flattening them to
  spaces, so multi-line messages survive end to end while each message still
  occupies exactly one physical line.
- **The server validates global messages**: blank messages are dropped and
  over-long ones are truncated to the shared `Protocol.MAX_MESSAGE_LENGTH`, so a
  malformed or abusive message can never crash the server or flood the room.
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
