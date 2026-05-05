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

## Sodium and Iris

This mod does not add client rendering code, renderer mixins, shaders, block
models, or HUD overlays. It is server-side gameplay logic, so it is designed to
coexist with Sodium and Iris in the client modpack.

## Build

With Java 25 available, from `D:\Addons\shared-health-fabric`:

```powershell
.\gradlew.bat build
```

The jar is generated in `shared-health-fabric\build\libs`.
