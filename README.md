# Discord Integration Velocity

Velocity proxy plugin that syncs chat between Discord and Minecraft, syncs chat across multiple backend servers, provides Discord slash commands for online status, and posts Discord embeds for proxy status, player join/leave, server switches, and deaths with coordinates.

Death coordinates are not available from Velocity alone, so this repository also includes a small Fabric backend mod for Minecraft 26.1.2. Install it on every Fabric backend server where death notifications should include coordinates.

## Build

```bash
./gradlew build
```

If the Gradle wrapper is not present, use a local Gradle installation:

```bash
gradle build
```

Minecraft 26.1.2 requires Java 25 for Fabric mod development. This repository includes a Gradle 9.5.0 wrapper and points Gradle at Homebrew OpenJDK 25 in `gradle.properties`.

Output jars:

- `velocity/build/libs/discord-integration-velocity-1.0.0.jar`
- `fabric-backend/build/libs/discord-integration-fabric-backend-1.0.0.jar`

## Release

Merges to `main` trigger `.github/workflows/release.yml`. The workflow bumps the patch version in `build.gradle.kts`, commits that bump with `[skip ci]`, builds both jars, and creates a GitHub Release tagged like `v1.0.1`.

## Install

1. Put the Velocity jar in the Velocity proxy `plugins` directory.
2. Start Velocity once to generate `plugins/discordintegrationvelocity/config.json`.
3. Set `discord.token` and `discord.channelId`.
4. Enable the Discord bot's Message Content Intent in the Discord Developer Portal.
5. Put the Fabric backend jar in each Fabric 26.1.2 backend server's `mods` directory.
6. Keep FabricProxy-Lite installed/configured on the Fabric backend servers as usual.
7. Restart the proxy and backend servers.

## Generated Config

```json
{
  "discord": {
    "enabled": true,
    "token": "",
    "channelId": "",
    "status": "Minecraft chat",
    "slashCommands": true,
    "minecraftChatWebhook": true,
    "minecraftChatWebhookName": "Minecraft Chat"
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
    "proxyStatus": true,
    "joinColor": 4437377,
    "leaveColor": 15746887,
    "switchColor": 16426522,
    "deathColor": 10038562,
    "overworldDeathColor": 10038562,
    "netherDeathColor": 11549230,
    "endDeathColor": 7419530,
    "proxyStartColor": 4437377,
    "proxyStopColor": 15746887
  }
}
```

Use exact Velocity backend server names in `syncedServers` to limit syncing, for example:

```json
"syncedServers": ["survival", "creative"]
```

## Notes

- Minecraft chat sent from one backend server is relayed to players on other synced backend servers.
- Discord messages are relayed to all players on synced backend servers.
- Discord attachments and stickers are relayed to Minecraft as `添付: URL`.
- The bot registers `/players`, `/servers`, and `/where <player>` as global slash commands when `discord.slashCommands` is enabled. Global command changes can take time to appear in Discord.
- Minecraft chat is posted to Discord through a channel webhook when `discord.minecraftChatWebhook` is enabled and the bot can manage webhooks. If webhook setup fails, the plugin falls back to normal bot messages.
- Proxy start/stop notifications are controlled by `embeds.proxyStatus`.
- Join/leave and server-switch notifications are handled entirely by Velocity.
- Death notifications require the Fabric backend mod because the proxy cannot see backend death events or world coordinates. Death embeds include dimension-specific colors, coordinates, and a copyable `/execute in ... run tp ...` command.
- The Fabric backend mod sends raw JSON on the `discordsync:events` custom payload channel through the player connection, which Velocity receives as a backend plugin message.
