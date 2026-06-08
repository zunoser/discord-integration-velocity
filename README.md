# Discord Integration Velocity

Velocity proxy plugin that syncs chat between Discord and Minecraft, syncs chat across multiple backend servers, and posts Discord embeds for player join/leave, server switches, and deaths with coordinates.

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
    "status": "Minecraft chat"
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

## Notes

- Minecraft chat sent from one backend server is relayed to players on other synced backend servers.
- Discord messages are relayed to all players on synced backend servers.
- Join/leave and server-switch notifications are handled entirely by Velocity.
- Death notifications require the Fabric backend mod because the proxy cannot see backend death events or world coordinates.
- The Fabric backend mod sends raw JSON on the `discordsync:events` custom payload channel through the player connection, which Velocity receives as a backend plugin message.
