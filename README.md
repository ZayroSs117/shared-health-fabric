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

## Compatibility

- Minecraft Java: 26.1.2
- Fabric Loader: 0.19.2 or newer
- Fabric API: required
- Java: 25 or newer
- Side: server-side mod. Install it on the dedicated server, or in the host
  client's mods folder for a LAN/integrated world.
