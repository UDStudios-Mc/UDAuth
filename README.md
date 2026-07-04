# UDAuth

UDAuth is a Paper authentication plugin for mixed premium/cracked (offline-mode)
Minecraft servers. Unauthenticated players are held at an authentication spawn
and cannot move, chat, use other commands, interact, manage inventory, take or
deal damage, drop/pick up items, or lose hunger.

## Commands

- `/login <password>` — log into a registered cracked account.
- `/register <password> <repeat>` — create a cracked account.
- `/premium` — provided by FastLogin; verifies a paid account for future bypass.
- `/authspawnstart` — set the block-centred waiting location (OP by default).
- `/authspawnend` — set the block-centred destination after authentication (OP by default).

## Build and install

1. Install Java 17+ and Maven.
2. Run `mvn clean package` in this directory.
3. Copy `target/UDAuth-1.1.2.jar` to the server's `plugins` directory.
4. Restart Paper, join as an operator, and set both spawn locations.
5. Configure the proxy/server for offline-mode players responsibly and protect
   backend server ports from direct access.

The project compiles against Paper 1.20.4 and uses Java 17 bytecode. It is also
intended to run on later compatible Paper releases.

## Premium bypass (important)

An offline-mode server cannot securely prove account ownership from a Minecraft
username or Mojang profile lookup. Doing that would let a cracked client choose
a premium player's name and bypass login.

For a safe premium bypass, install and correctly configure FastLogin. A paid
player is registered/logged in automatically through FastLogin's native
`AuthPlugin` hook. UDAuth registers that hook at startup, so FastLogin recognizes
UDAuth as the server's offline authentication plugin. FastLogin exclusively owns
the `/premium` command so it can perform Mojang verification before UDAuth trusts
the connection. Once verified, FastLogin calls UDAuth's hook to create/login the
account and remove the authentication restrictions.

If the server is behind Velocity or BungeeCord, secure the backend and configure
player information forwarding correctly. Never expose an offline-mode backend
directly to the internet.

## Data and security

Accounts are stored in `plugins/UDAuth/users.yml`. Passwords are salted and
hashed with PBKDF2-HMAC-SHA256 (210,000 iterations by default); plaintext
passwords are never stored. Password hashing runs asynchronously to avoid
stalling the server tick. Back up `users.yml` and do not publish it.
