# Shared Health Fabric

Fabric server mod for Minecraft Java 26.1.2. It recreates the shared health mode
from Shytoos' Paper plugin for Fabric servers and integrated worlds.

## Features

- Shared health across online players.
- Shared hunger across online players.
- Shared absorption hearts.
- All active potion/status effects are shared across online players.
- One shared death: if one player dies, every online player dies.
- Shared death cleanup: online inventories, armor, offhand, ender chests, death
  drops, and ground items are cleared.
- Offline players who missed a shared death have their inventory and ender chest
  cleared the next time they join.
- Configurable natural mob spawn multiplier.

## Configuration

The server config file is `config/shared_health_fabric.json`.

```json
{
  "mobSpawnMultiplier": 1.0
}
```

`1.0` keeps vanilla spawn caps. `1.2` allows about 20% more natural mobs, `2.0`
roughly doubles the cap, and `5.0` is the maximum.

Operators can change it in game:

```text
/sharedhealth mobspawn 1.2
```

## Compatibility

- Minecraft Java: 26.1.2
- Fabric Loader: 0.19.2 or newer
- Fabric API: required
- Java: 25 or newer. Java 21 is not enough for Minecraft 26.1.2.
- Side: server-side mod. Install it on the dedicated server, or in the host
  client's mods folder for a LAN/integrated world.
