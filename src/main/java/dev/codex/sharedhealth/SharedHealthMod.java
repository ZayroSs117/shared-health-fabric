package dev.codex.sharedhealth;

import net.fabricmc.api.ModInitializer;

public final class SharedHealthMod implements ModInitializer {
    public static final String MOD_ID = "shared_health_fabric";

    @Override
    public void onInitialize() {
        SharedHealthController.INSTANCE.register();
    }
}
