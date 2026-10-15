# Group Death

A Minecraft server mod: when one player dies, everyone dies.

Made for hardcore runs with friends, where the whole group wins or loses together. It runs on the server [minecraft-server](https://github.com/Ractouf/minecraft-server).

## What it does

- When a player dies, every other online player is killed too and sees the same death message.
- Two game rules, both on by default:

| Game rule | Default | Effect |
| --- | --- | --- |
| `groupdeath:shared_health_pool` | `true` | All players share one health bar |
| `groupdeath:mirror_damage` | `true` | Damage taken by one player is dealt to everyone |

```
/gamerule groupdeath:shared_health_pool false
```

## Install

Server-side only, players don't need to install anything.

1. Install [Fabric](https://fabricmc.net/use/server/) on the server (Minecraft 26.3) with [Fabric API](https://modrinth.com/mod/fabric-api).
2. Build the mod with `./gradlew build` (needs Java 25+).
3. Copy `build/libs/groupdeath-*.jar` into the server's `mods/` folder.

Built with Java and the Fabric modding API.
