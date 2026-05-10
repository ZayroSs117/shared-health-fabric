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
- Offline players are not affected by shared death cleanup.
- Configurable natural mob spawn multiplier.
- Configurable animal breeding cooldown and baby growth time.
- Configurable chicken egg lay time.
- Server-side force-loaded chunk commands.

## Configuration

The server config file is `config/shared_health_fabric.json`.

```json
{
  "mobSpawnMultiplier": 1.0,
  "animalBreedingCooldownMultiplier": 1.0,
  "animalGrowthTimeMultiplier": 1.0,
  "chickenEggLayTimeMultiplier": 1.0
}
```

`1.0` keeps vanilla spawn caps. `1.2` allows about 20% more natural mobs, `2.0`
roughly doubles the cap, and `5.0` is the maximum.

Animal time multipliers use `1.0` for vanilla time. Lower values are faster:
`0.5` halves the cooldown/growth time, and `0.05` is the minimum.
Chicken egg lay time also uses this range. Vanilla is about 5 to 10 minutes;
`0.5` makes it about 2.5 to 5 minutes.

Operators can change it in game:

```text
/sharedhealth mobspawn 1.2
/sharedhealth animal breeding 0.5
/sharedhealth animal growth 0.5
/sharedhealth animal eggs 0.5
/sharedhealth chunkload status
/sharedhealth chunkload add
/sharedhealth chunkload remove
/sharedhealth chunkload list
```

`/sharedhealth chunkload add` force-loads the chunk where the operator is
standing. `/sharedhealth chunkload list` shows the force-loaded chunks in the
operator's current dimension.

## Compatibility

- Minecraft Java: 26.1.2
- Fabric Loader: 0.19.2 or newer
- Fabric API: required
- Java: 25 or newer. Java 21 is not enough for Minecraft 26.1.2.
- Side: server-side mod. Install it on the dedicated server, or in the host
  client's mods folder for a LAN/integrated world.
