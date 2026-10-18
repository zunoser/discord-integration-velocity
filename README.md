# Discord Integration Velocity

Velocity proxy plugin that syncs chat between Discord and Minecraft, syncs chat across multiple backend servers, and posts Discord embeds for player join/leave, server switches, and deaths with coordinates.

Death coordinates are not available from Velocity alone, so this repository also includes a small server-only Fabric backend mod. Each supported Minecraft version has its own jar; install the jar matching the **backend server** version. Players do not need the mod.

## Supported Minecraft versions

| Fabric backend server | Required server Java |
| --- | --- |
| 1.20.1, 1.20.2, 1.20.4 | 17+ |
| 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11 | 21+ |
| 26.1.2, 26.2, 26.3 | 25+ |

These are separate, exact build targets, not a claim that one mod jar works across Minecraft versions. Other patch releases and snapshots are not enabled. The authoritative list and Fabric API dependencies are in `fabric-backend/versions.properties`.

The Velocity jar is shared by all backend versions. Use a Velocity release that supports the Minecraft client and backend versions you run. Changing this plugin cannot add protocol support to Velocity. Use Java 21 or newer for the proxy, or the newer Java required by your Velocity release.

## Build

Use JDK 25 to run Gradle and compile the project. The compiler targets Java 17, 21, or 25 for the selected backend, as shown above. The Velocity plugin targets Java 21. Set `JAVA_HOME` to your JDK 25 installation; no machine-specific path is configured in the repository.

```bash
# Velocity plugin and the default Fabric backend (26.3)
./gradlew build

# A particular Fabric server version
./gradlew :fabric-backend:build -PminecraftVersion=1.21.1

# Only the proxy plugin, without configuring the Fabric project
./gradlew :velocity:build --configure-on-demand

# All supported backend jars
while IFS='=' read -r minecraft api; do
  case "$minecraft" in ''|\#*) continue ;; esac
  ./gradlew :fabric-backend:build -PminecraftVersion="$minecraft" || exit 1
done < fabric-backend/versions.properties
```

Output jars (replace `<version>` with the project release version):

- `velocity/build/libs/discord-integration-velocity-<version>.jar`
- `fabric-backend/build/<minecraft>/libs/discord-integration-fabric-backend-<version>-mc<minecraft>.jar`

For Minecraft 1.21.11 and older, Loom remaps the Minecraft references to Fabric's production namespace. The additional `-dev.jar` is for development only; do not install it on a server. For 26.x, Minecraft is unobfuscated and does not need remapping.

`build` runs an assertion-based check of the JSON protocol shared with Velocity, including Unicode, escaped control characters, and negative coordinates. CI compiles and checks every supported version. These checks do not replace testing player deaths, client connections, and Discord delivery on a running proxy/backend network.

## Release

Merges to `main` trigger `.github/workflows/release.yml`. The workflow bumps the patch version in `build.gradle.kts`, commits that bump with `[skip ci]`, and invokes `.github/workflows/build.yml` to build the Velocity jar and all Fabric targets in parallel. It creates a GitHub Release tagged like `v1.0.3` only after every build passes. Pull requests also run the complete build matrix.

## Install

1. Put the Velocity jar in the Velocity proxy `plugins` directory.
2. Start Velocity once to generate `plugins/discordintegrationvelocity/config.json`.
3. Set `discord.token` and `discord.channelId`.
4. Enable the Discord bot's Message Content Intent in the Discord Developer Portal.
5. On each Fabric backend, install Fabric Loader 0.19.3 or newer and the Fabric API version listed for that Minecraft version in `fabric-backend/versions.properties` (or a compatible newer version).
6. Put **one** matching `-mc<minecraft>.jar` in that backend's `mods` directory. Remove any previous backend jar first. Different backend versions can share the same Velocity proxy and Discord channel.
7. Keep FabricProxy-Lite installed/configured on the Fabric backend servers as usual, using its version for that server's Minecraft release.
8. Restart the proxy and backend servers.

## Generated Config

```json
{
  "discord": {
    "enabled": true,
    "token": "",
    "channelId": "",
    "status": "Minecraft chat"
  },
  "authentication": {
    "enabled": false
  },
  "minecraft": {
    "broadcastDiscordMessages": true,
    "broadcastMinecraftChatToOtherServers": true,
    "syncedServers": ["*"]
  },
  "embeds": {
    "joinLeave": true,
    "serverSwitch": true,
    "death": true,
    "joinColor": 4437377,
    "leaveColor": 15746887,
    "switchColor": 16426522,
    "deathColor": 10038562
  }
}
```

Use exact Velocity backend server names in `syncedServers` to limit syncing, for example:

```json
"syncedServers": ["survival", "creative"]
```

## Discord authentication / proxy whitelist

Authentication is opt-in so upgrading an existing server does not unexpectedly lock everyone out. Add `"authentication": {"enabled": true}` to `plugins/discordintegrationvelocity/config.json` and restart Velocity. Keep `online-mode = true` in `velocity.toml`; offline-mode connections are rejected when authentication is enabled. Backends must accept connections only through your authenticated proxy, with forwarding configured correctly.

The bot uses the Discord server containing `discord.channelId`. Invite it with the `bot` and `applications.commands` scopes and allow members to use application commands. Every member of that Discord server who can use `/認証` can register. Restrict command access using Discord's server integration permissions if you want to limit registration to specific roles or channels. Direct messages and other Discord servers are rejected.

1. Connect to Minecraft through Velocity using your own Java Edition account. The proxy authenticates the account with Mojang, then disconnects unregistered players with a random 16-character code.
2. In the configured Discord server, run `/認証 code:YOUR_CODE`. The code expires after 10 minutes and can only be used once. Keep it private: it proves ownership of the Minecraft account to Discord.
3. Reconnect to Minecraft. The UUID is now allowed across the entire proxy, independently of `syncedServers` and backend Minecraft versions.

Commands reply privately (ephemeral). Each Discord account can link one Minecraft UUID, and each UUID can link one Discord account. Renaming a Minecraft account does not revoke access. There is no in-game command or backend mod requirement for authentication.

- `/認証 code:...` — link your Minecraft account and add its UUID to the whitelist.
- `/認証解除` — remove your own registration and disconnect that Minecraft account if online.
- `/認証解除 user:@member` — remove another member's registration; requires Discord's **Manage Server** permission, checked when the command runs. This is a reset, not a ban: the member may register again if they can still use `/認証`.

Registrations are saved in `plugins/discordintegrationvelocity/discord-whitelist.json` using an atomic file replacement. Back up this file. Edit it only while the proxy is stopped. A corrupt/unreadable list or config blocks logins rather than admitting everyone; fix the file and restart. Registration changes take effect only after a successful save. Codes are held only in memory and disappear on restart. Existing registrations can connect during Discord outages; new registrations require the bot to be available.

Discord membership is checked when registering, not continuously: leaving Discord or losing a role does not automatically revoke an existing registration. Use `/認証解除 user:@member` to revoke it. Disable command access as well when blocking re-registration.

`./gradlew :velocity:build --configure-on-demand` runs offline assertion checks for linking, expiry, one-time use, persistence and failed saves, duplicate accounts, config/list corruption, online-mode enforcement, legacy config migration, and slash command guild/permission checks. A live Discord bot and Minecraft connection still need to be tested on your server.

## Notes

- Minecraft chat sent from one backend server is relayed to players on other synced backend servers.
- Discord messages are relayed to all players on synced backend servers.
- Join/leave and server-switch notifications are handled entirely by Velocity.
- Death notifications require the Fabric backend mod because the proxy cannot see backend death events or world coordinates.
- The Fabric backend mod sends raw JSON on the `discordsync:events` custom payload channel through the player connection, which Velocity receives as a backend plugin message.
