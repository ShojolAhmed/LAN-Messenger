# LAN Messenger — Windows Deployment Guide

This package contains two self-contained Windows applications:

| Application | Folder | Run this |
|-------------|--------|----------|
| **Server** (run once, on one machine) | `LAN Messenger Server\` | `LAN Messenger Server.exe` |
| **Client** (run on every user's machine) | `LAN Messenger\` | `LAN Messenger.exe` |

Each application **bundles its own Java runtime** (and the client bundles JavaFX and
SQLite). **You do not need to install Java** or anything else to run them.

- Supported OS: **Windows 10 / 11, 64-bit**
- The server and all clients must be on the **same Local Area Network** (same
  Wi-Fi / switch / subnet).

---

## 1. Install

These are **portable** applications — there is no installer.

1. Unzip the distribution:
   - `LAN-Messenger-Server-1.0-win-x64.zip` → gives you a `LAN Messenger Server` folder.
   - `LAN-Messenger-1.0-win-x64.zip` → gives you a `LAN Messenger` folder.
2. Copy each folder wherever you like, for example:
   - `C:\Program Files\LAN Messenger Server` (copying here may prompt for administrator rights), or
   - simply your Desktop or `C:\Apps\`.
3. Run the application by double-clicking the `.exe` inside its folder.
   Optionally right-click the `.exe` → **Send to → Desktop (create shortcut)**.

> Keep each application's folder intact — the `.exe`, `app\`, and `runtime\`
> folders belong together.

To **uninstall**, just delete the folder (and any firewall rule you added — see
below). Chat history is stored separately (see [Data & privacy](#5-data--privacy)).

---

## 2. Start the server

Run the server on **one** machine that the others can reach over the LAN.

1. Open the `LAN Messenger Server` folder and double-click **`LAN Messenger Server.exe`**.
   A console window opens and prints something like:

   ```
   LAN Messenger server v1.0 ready on port 5000 (press Ctrl+C to stop)
   ```

2. Leave this window open while people are chatting.
   Press **`Ctrl+C`** (or close the window) to stop the server.

### Using a different port

The default port is **5000**. To use another port, start the server from a
terminal (Command Prompt or PowerShell) opened in the server folder:

```powershell
.\"LAN Messenger Server.exe" 5050
```

Whatever port you choose, use the **same** port in every client and in the
firewall rule below.

---

## 3. Find the server's LAN IP address

Clients connect to the server by its **IPv4 address** on the LAN. On the **server**
machine, open PowerShell and run:

```powershell
ipconfig
```

Look under your active adapter (usually **Wireless LAN adapter Wi-Fi** or
**Ethernet**) for the **IPv4 Address**, e.g. `192.168.10.243`.

A quick one-liner that prints just the likely LAN addresses:

```powershell
Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
  Select-Object IPAddress, InterfaceAlias
```

- Home/office LAN addresses usually start with `192.168.` or `10.` or `172.`.
- If the server and client run on the **same** computer, clients can use
  `localhost` instead of an IP.

---

## 4. Allow the server through Windows Firewall

Windows blocks incoming connections by default, so the **server machine** must
allow inbound TCP traffic on the server port (default **5000**). Clients do **not**
need a firewall rule.

### Option A — accept the prompt (easiest)

The first time you start the server, **Windows Defender Firewall** may pop up a
dialog. Tick **Private networks** and click **Allow access**.

### Option B — add a rule manually (PowerShell as Administrator)

If you saw no prompt, or clients still cannot connect, open PowerShell **as
Administrator** and run (adjust the port if you changed it):

```powershell
New-NetFirewallRule -DisplayName "LAN Messenger Server" `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5000 -Profile Private
```

Equivalent using `netsh` (also as Administrator):

```cmd
netsh advfirewall firewall add rule name="LAN Messenger Server" dir=in action=allow protocol=TCP localport=5000
```

To remove the rule later:

```powershell
Remove-NetFirewallRule -DisplayName "LAN Messenger Server"
```

> Make sure the network is set to **Private**, not Public. Public networks block
> most incoming connections. Check under **Settings → Network & Internet**.

---

## 5. Connect clients

On each user's machine:

1. Double-click **`LAN Messenger.exe`**. The **connection screen** appears.
2. Enter:
   - **Username** — 1–24 characters (letters, digits, `.`, `_`, `-`); must be unique.
   - **Server IP** — the server's LAN IPv4 address from step 3 (e.g. `192.168.10.243`),
     or `localhost` if the client is on the same machine as the server.
   - **Port** — the server's port (default `5000`).
3. Click **Connect**.

On success you land in the shared **Global** room. Everyone else who is online
appears in the sidebar — click a name to start a **private** one-to-one chat.
Repeat on as many machines as you like (each with a different username).

---

## 6. Data & privacy

- Chat history is saved **locally on each client** in a small SQLite database at:
  `%LOCALAPPDATA%\LanMessenger\chat-history.db`
  (typically `C:\Users\<you>\AppData\Local\LanMessenger\`).
- The server does **not** store messages; it only routes them between connected
  clients while it is running.
- Traffic is sent over the LAN in plain text (no encryption), which is appropriate
  for a trusted local network.

---

## 7. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Client says **"Unable to connect to server."** | Confirm the server is running; check the IP and port are correct; verify the firewall rule on the server (step 4); ensure both machines are on the same LAN/subnet. |
| Works with `localhost` but not from another PC | It's almost always the **firewall** or a **Public** network profile on the server. See step 4. |
| **"username ... is already taken"** | Someone is already using that name. Pick a different username. |
| Server prints **"Could not start server ... Address already in use"** | Another program (or a second server) is using the port. Choose a different port (step 2) and update clients/firewall to match. |
| Client window doesn't appear | Make sure you extracted the whole `LAN Messenger` folder and run the `.exe` from inside it (don't move the `.exe` out on its own). |
| Can't tell the server's IP | Run `ipconfig` on the **server** machine (step 3). Give clients that IPv4 address. |

---

### Quick reference

```
Server machine:  run "LAN Messenger Server.exe"   (default port 5000)
                 allow inbound TCP 5000 in Windows Firewall (Private)
                 find IP with: ipconfig            (e.g. 192.168.10.243)

Client machines: run "LAN Messenger.exe"
                 Username + Server IP (192.168.10.243) + Port (5000) + Connect
```
