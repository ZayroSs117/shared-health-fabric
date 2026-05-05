package dev.codex.sharedhealth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;

public enum SharedHealthController {
    INSTANCE;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double EPSILON = 1.0E-4D;
    private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 60;

    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private SharedHealthData data = new SharedHealthData();
    private MinecraftServer server;
    private Path dataPath;
    private boolean syncing;
    private boolean massKillInProgress;
    private int autosaveTicks;

    private final Map<Holder<MobEffect>, EffectSnapshot> sharedEffects = new HashMap<>();

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, currentServer) -> onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, currentServer) -> {
            snapshots.remove(handler.player.getUUID());
            if (currentServer.getPlayerList().getPlayerCount() <= 1) {
                saveData();
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            syncPlayerToSharedState(newPlayer);
            markPlayerDeathSynced(newPlayer.getUUID(), data.sharedDeathCounter);
            snapshots.put(newPlayer.getUUID(), PlayerSnapshot.capture(newPlayer));
        });
    }

    private void onServerStarted(MinecraftServer currentServer) {
        server = currentServer;
        dataPath = FabricLoader.getInstance().getConfigDir().resolve(SharedHealthMod.MOD_ID + ".json");
        loadData();
        initializeFromOnlinePlayers();
        refreshSnapshots();
    }

    private void onServerStopping(MinecraftServer currentServer) {
        saveData();
        snapshots.clear();
        server = null;
    }

    private void onPlayerJoin(ServerPlayer player) {
        applyMissedSharedDeathStateIfNeeded(player);
        syncPlayerToSharedState(player);
        markPlayerDeathSynced(player.getUUID(), data.sharedDeathCounter);
        snapshots.put(player.getUUID(), PlayerSnapshot.capture(player));
    }

    private void onServerTick(MinecraftServer currentServer) {
        if (server == null) {
            server = currentServer;
        }

        List<ServerPlayer> players = currentServer.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            ensureDefaults();
            autosave(currentServer);
            return;
        }

        if (massKillInProgress) {
            refreshSnapshots();
            autosave(currentServer);
            return;
        }

        Optional<ServerPlayer> deadPlayer = players.stream()
                .filter(player -> player.isDeadOrDying() || player.getHealth() <= 0.0F)
                .findFirst();
        if (deadPlayer.isPresent()) {
            handleSharedDeath(deadPlayer.get());
            refreshSnapshots();
            autosave(currentServer);
            return;
        }

        if (snapshots.isEmpty()) {
            initializeFromOnlinePlayers();
            refreshSnapshots();
            autosave(currentServer);
            return;
        }

        detectAndApplySharedChanges(players);
        refreshSnapshots();
        autosave(currentServer);
    }

    private void detectAndApplySharedChanges(List<ServerPlayer> players) {
        if (syncing) {
            return;
        }

        ServerPlayer potionSource = null;
        ServerPlayer strongestDamageSource = null;
        ServerPlayer strongestHealSource = null;
        double strongestDamage = EPSILON;
        double strongestHeal = EPSILON;
        ServerPlayer hungerDecreaseSource = null;
        ServerPlayer hungerIncreaseSource = null;
        int largestFoodDrop = 0;
        int largestFoodGain = 0;

        for (ServerPlayer player : players) {
            PlayerSnapshot previous = snapshots.get(player.getUUID());
            if (previous == null) {
                continue;
            }

            PlayerSnapshot current = PlayerSnapshot.capture(player);
            if (previous.hasSharedEffectUpdate(current)) {
                potionSource = player;
            }

            double previousTotal = previous.health + previous.absorption;
            double currentTotal = current.health + current.absorption;
            double totalDelta = currentTotal - previousTotal;
            if (totalDelta < -strongestDamage) {
                strongestDamage = -totalDelta;
                strongestDamageSource = player;
            } else if (totalDelta > strongestHeal && isNaturalRegenController(player, players)) {
                strongestHeal = totalDelta;
                strongestHealSource = player;
            }

            int foodDelta = current.foodLevel - previous.foodLevel;
            if (foodDelta < -largestFoodDrop) {
                largestFoodDrop = -foodDelta;
                hungerDecreaseSource = player;
            } else if (foodDelta > largestFoodGain) {
                largestFoodGain = foodDelta;
                hungerIncreaseSource = player;
            }
        }

        if (potionSource != null) {
            syncSharedPotionEffectsFromSource(potionSource);
        }

        if (strongestDamageSource != null) {
            syncSharedHealthFromPlayer(strongestDamageSource, strongestDamage, true);
        } else if (strongestHealSource != null) {
            syncSharedHealthFromPlayer(strongestHealSource, strongestHeal, false);
        }

        if (hungerDecreaseSource != null) {
            syncHungerFromPlayer(hungerDecreaseSource);
        } else if (hungerIncreaseSource != null) {
            syncHungerFromPlayer(hungerIncreaseSource);
        }
    }

    private void initializeFromOnlinePlayers() {
        if (server == null) {
            return;
        }

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            ensureDefaults();
            saveData();
            return;
        }

        double lowestHealth = findLowestAliveHealth(null);
        if (lowestHealth >= 0.0D) {
            updateSharedHealth(lowestHealth);
        } else if (!data.hasSharedHealth || data.sharedHealth <= 0.0D) {
            updateSharedHealth(resolvePlayerMaxHealth(players.getFirst()));
        }
        applySharedHealthToAll(data.sharedHealth);

        ServerPlayer lowestFoodPlayer = findPlayerWithLowestFood(null);
        if (lowestFoodPlayer != null) {
            updateSharedHunger(lowestFoodPlayer.getFoodData().getFoodLevel());
        } else if (!data.hasSharedHunger) {
            updateSharedHunger(players.getFirst().getFoodData().getFoodLevel());
        }
        applySharedHungerToAll();

        setSharedEffectsFromOnlinePlayers();
        applySharedEffects();

        double lowestAbsorption = findLowestAliveAbsorption(null);
        if (lowestAbsorption >= 0.0D) {
            updateSharedAbsorption(lowestAbsorption);
        } else if (!data.hasSharedAbsorption) {
            updateSharedAbsorption(0.0D);
        }
        applySharedAbsorptionToAll();
    }

    private void ensureDefaults() {
        if (!data.hasSharedHealth) {
            updateSharedHealth(20.0D);
        }
        if (!data.hasSharedHunger) {
            updateSharedHunger(20);
        }
        if (!data.hasSharedAbsorption) {
            updateSharedAbsorption(0.0D);
        }
    }

    private void syncPlayerToSharedState(ServerPlayer player) {
        if (server == null) {
            return;
        }

        syncPlayerToSharedPotionEffects(player);
        syncPlayerToSharedHealth(player);
        syncPlayerToSharedAbsorption(player);
        syncPlayerToSharedHunger(player);
    }

    private void syncPlayerToSharedHealth(ServerPlayer player) {
        double targetHealth = findLowestAliveHealth(player);
        if (targetHealth < 0.0D) {
            if (data.hasSharedHealth && data.sharedHealth > 0.0D) {
                targetHealth = data.sharedHealth;
            } else {
                targetHealth = resolvePlayerMaxHealth(player);
            }
        }

        updateSharedHealth(targetHealth);
        double clamped = clampHealthForPlayer(player, targetHealth);

        syncing = true;
        try {
            if (clamped <= 0.0D) {
                player.setHealth(0.0F);
            } else if (!player.isDeadOrDying()) {
                player.setHealth((float) clamped);
            }
        } finally {
            syncing = false;
        }
    }

    private void syncPlayerToSharedHunger(ServerPlayer player) {
        ensureSharedHungerInitialized(player);
        int targetFood = data.sharedFoodLevel;

        ServerPlayer lowestFoodPlayer = findPlayerWithLowestFood(player);
        if (lowestFoodPlayer != null) {
            targetFood = lowestFoodPlayer.getFoodData().getFoodLevel();
            updateSharedHunger(targetFood);
        }

        syncing = true;
        try {
            player.getFoodData().setFoodLevel(clampFoodLevel(targetFood));
        } finally {
            syncing = false;
        }
    }

    private void syncPlayerToSharedAbsorption(ServerPlayer player) {
        ensureSharedAbsorptionInitialized(player);
        double targetAbsorption = data.sharedAbsorption;
        double lowestAbsorption = findLowestAliveAbsorption(player);
        if (lowestAbsorption >= 0.0D) {
            targetAbsorption = lowestAbsorption;
            updateSharedAbsorption(targetAbsorption);
        }

        player.setAbsorptionAmount((float) clampAbsorptionForPlayer(player, targetAbsorption));
    }

    private void syncHungerFromPlayer(ServerPlayer player) {
        updateSharedHunger(player.getFoodData().getFoodLevel());
        applySharedHungerToAll();
    }

    private void syncSharedHealthFromPlayer(ServerPlayer source, double changedAmount, boolean loss) {
        if (source.isDeadOrDying() || source.getHealth() <= 0.0F) {
            updateSharedHealth(0.0D);
            updateSharedAbsorption(0.0D);
            killAllPlayers();
            return;
        }

        ensureSharedHealthInitialized(source);
        ensureSharedAbsorptionInitialized(source);
        double maxSharedHealth = resolveSharedHealthCap(source);
        double newSharedHealth = Math.min(source.getHealth(), maxSharedHealth);
        double maxSharedAbsorption = resolveSharedAbsorptionCap(source);
        double newSharedAbsorption = Math.min(source.getAbsorptionAmount(), maxSharedAbsorption);
        double previousSharedHealth = data.sharedHealth;
        double previousSharedAbsorption = data.sharedAbsorption;
        updateSharedHealth(newSharedHealth);
        updateSharedAbsorption(newSharedAbsorption);

        if (loss) {
            boolean absorptionOnlyLoss = newSharedAbsorption < previousSharedAbsorption
                    && Math.abs(newSharedHealth - previousSharedHealth) <= EPSILON;
            sendSharedDamageActionBar(source.getPlainTextName(), changedAmount, absorptionOnlyLoss);
        }

        applySharedStateToOthers(source, newSharedHealth, newSharedAbsorption, loss);
    }

    private void syncSharedPotionEffectsFromSource(ServerPlayer source) {
        setSharedEffectsFromSource(source);
        applySharedEffects();
        syncSharedAbsorptionFromPlayer(source);
    }

    private void syncSharedAbsorptionFromPlayer(ServerPlayer source) {
        ensureSharedAbsorptionInitialized(source);
        double maxSharedAbsorption = resolveSharedAbsorptionCap(source);
        updateSharedAbsorption(Math.min(source.getAbsorptionAmount(), maxSharedAbsorption));
        applySharedAbsorptionToAll();
    }

    private void applySharedHealthToAll(double health) {
        if (server == null) {
            return;
        }

        syncing = true;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.isDeadOrDying()) {
                    continue;
                }

                double clamped = clampHealthForPlayer(player, health);
                if (clamped <= 0.0D) {
                    player.setHealth(0.0F);
                } else {
                    player.setHealth((float) clamped);
                }
            }
        } finally {
            syncing = false;
        }
    }

    private void applySharedStateToOthers(ServerPlayer source, double health, double absorption, boolean showDamageFeedback) {
        if (server == null) {
            return;
        }

        syncing = true;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getUUID().equals(source.getUUID()) || player.isDeadOrDying()) {
                    continue;
                }

                double clampedHealth = clampHealthForPlayer(player, health);
                player.setHealth((float) clampedHealth);
                player.setAbsorptionAmount((float) clampAbsorptionForPlayer(player, absorption));
                if (showDamageFeedback) {
                    playDamageFeedback(player);
                }
            }
        } finally {
            syncing = false;
        }
    }

    private void applySharedHungerToAll() {
        if (server == null) {
            return;
        }

        syncing = true;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.getFoodData().setFoodLevel(data.sharedFoodLevel);
            }
        } finally {
            syncing = false;
        }
    }

    private void applySharedAbsorptionToAll() {
        if (server == null) {
            return;
        }

        syncing = true;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.isDeadOrDying()) {
                    player.setAbsorptionAmount((float) clampAbsorptionForPlayer(player, data.sharedAbsorption));
                }
            }
        } finally {
            syncing = false;
        }
    }

    private void applySharedEffects() {
        if (server == null) {
            return;
        }

        syncing = true;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                applySharedEffectsToPlayer(player);
            }
        } finally {
            syncing = false;
        }

        clampSharedHealthToCurrentCap();
        clampSharedAbsorptionToCurrentCap();
    }

    private void killAllPlayers() {
        if (server == null) {
            return;
        }

        massKillInProgress = true;
        syncing = true;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.isDeadOrDying()) {
                    player.setHealth(0.0F);
                }
            }
        } finally {
            syncing = false;
            massKillInProgress = false;
        }
    }

    private void handleSharedDeath(ServerPlayer source) {
        if (server == null) {
            return;
        }

        broadcastSharedKilledChat(source.getPlainTextName());
        wipeAllOnlineInventories();
        wipeAllOnlineEnderChests();
        clearAllGroundItems();
        recordSharedDeathForOnlinePlayers();
        updateSharedHealth(0.0D);
        updateSharedAbsorption(0.0D);
        updateSharedHunger(20);
        sharedEffects.clear();
        killAllPlayers();
        clearAllGroundItems();
        saveData();
    }

    private void applyMissedSharedDeathStateIfNeeded(ServerPlayer player) {
        long syncedDeathCounter = getPlayerDeathSync(player.getUUID());
        if (syncedDeathCounter >= data.sharedDeathCounter) {
            return;
        }

        wipePlayerInventory(player);
        player.getEnderChestInventory().clearContent();
    }

    private void recordSharedDeathForOnlinePlayers() {
        data.sharedDeathCounter++;
        data.dirty = true;
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            markPlayerDeathSynced(player.getUUID(), data.sharedDeathCounter);
        }
    }

    private void wipePlayerInventory(ServerPlayer player) {
        player.getInventory().clearContent();
    }

    private void wipeAllOnlineInventories() {
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            wipePlayerInventory(player);
        }
    }

    private void wipeAllOnlineEnderChests() {
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.getEnderChestInventory().clearContent();
        }
    }

    private void clearAllGroundItems() {
        if (server == null) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            for (ItemEntity item : level.getEntities(EntityType.ITEM, item -> true)) {
                item.discard();
            }
        }
    }

    private void playDamageFeedback(ServerPlayer player) {
        player.level().playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                SoundEvents.PLAYER_HURT,
                SoundSource.PLAYERS,
                1.0F,
                1.0F);
    }

    private void sendSharedDamageActionBar(String playerName, double sharedDamage, boolean absorptionOnlyLoss) {
        if (server == null) {
            return;
        }

        String amountText = formatHeartsAmount(sharedDamage);
        ChatFormatting heartColor = absorptionOnlyLoss ? ChatFormatting.YELLOW : ChatFormatting.RED;
        Component message = Component.empty()
                .append(Component.literal(playerName).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" a perdu ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(amountText).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("\u2764").withStyle(heartColor));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isDeadOrDying()) {
                player.sendOverlayMessage(message);
            }
        }
    }

    private void broadcastSharedKilledChat(String playerName) {
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(playerName + " s'est fait tuer."), false);
        }
    }

    private String formatHeartsAmount(double damageInHealthPoints) {
        double hearts = damageInHealthPoints / 2.0D;
        double displayed = Math.round(hearts * 2.0D) / 2.0D;
        if (Math.abs(displayed - Math.rint(displayed)) <= EPSILON) {
            return String.format(Locale.US, "%.0f", displayed);
        }

        return String.format(Locale.US, "%.1f", displayed);
    }

    private boolean isNaturalRegenController(ServerPlayer player, List<ServerPlayer> players) {
        ServerPlayer controller = null;
        for (ServerPlayer candidate : players) {
            if (candidate.isDeadOrDying()) {
                continue;
            }

            if (controller == null || candidate.getUUID().compareTo(controller.getUUID()) < 0) {
                controller = candidate;
            }
        }

        return controller != null && controller.getUUID().equals(player.getUUID());
    }

    private void ensureSharedHealthInitialized(ServerPlayer referencePlayer) {
        if (data.hasSharedHealth) {
            return;
        }

        double lowestAlive = findLowestAliveHealth(null);
        if (lowestAlive >= 0.0D) {
            updateSharedHealth(lowestAlive);
        } else if (referencePlayer != null) {
            updateSharedHealth(referencePlayer.getHealth());
        } else {
            updateSharedHealth(20.0D);
        }
    }

    private void ensureSharedHungerInitialized(ServerPlayer referencePlayer) {
        if (data.hasSharedHunger) {
            return;
        }

        ServerPlayer lowestFoodPlayer = findPlayerWithLowestFood(null);
        if (lowestFoodPlayer != null) {
            updateSharedHunger(lowestFoodPlayer.getFoodData().getFoodLevel());
        } else if (referencePlayer != null) {
            updateSharedHunger(referencePlayer.getFoodData().getFoodLevel());
        } else {
            updateSharedHunger(20);
        }
    }

    private void ensureSharedAbsorptionInitialized(ServerPlayer referencePlayer) {
        if (data.hasSharedAbsorption) {
            return;
        }

        double lowestAbsorption = findLowestAliveAbsorption(null);
        if (lowestAbsorption >= 0.0D) {
            updateSharedAbsorption(lowestAbsorption);
        } else if (referencePlayer != null) {
            updateSharedAbsorption(referencePlayer.getAbsorptionAmount());
        } else {
            updateSharedAbsorption(0.0D);
        }
    }

    private void setSharedEffectsFromSource(ServerPlayer source) {
        sharedEffects.clear();
        sharedEffects.putAll(captureEffects(source));
    }

    private void setSharedEffectsFromOnlinePlayers() {
        sharedEffects.clear();
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (Map.Entry<Holder<MobEffect>, EffectSnapshot> entry : captureEffects(player).entrySet()) {
                sharedEffects.merge(entry.getKey(), entry.getValue(), EffectSnapshot::strongest);
            }
        }
    }

    private void syncPlayerToSharedPotionEffects(ServerPlayer player) {
        syncing = true;
        try {
            applySharedEffectsToPlayer(player);
        } finally {
            syncing = false;
        }

        clampSharedHealthToCurrentCap();
        clampSharedAbsorptionToCurrentCap();
    }

    private double resolveSharedHealthCap(ServerPlayer fallbackPlayer) {
        double minMaxHealth = Double.MAX_VALUE;
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                minMaxHealth = Math.min(minMaxHealth, resolvePlayerMaxHealth(player));
            }
        }

        if (minMaxHealth == Double.MAX_VALUE) {
            return fallbackPlayer == null ? 20.0D : resolvePlayerMaxHealth(fallbackPlayer);
        }

        return minMaxHealth;
    }

    private double resolveSharedAbsorptionCap(ServerPlayer fallbackPlayer) {
        double minMaxAbsorption = Double.MAX_VALUE;
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                minMaxAbsorption = Math.min(minMaxAbsorption, resolvePlayerMaxAbsorption(player));
            }
        }

        if (minMaxAbsorption == Double.MAX_VALUE) {
            return fallbackPlayer == null ? 0.0D : resolvePlayerMaxAbsorption(fallbackPlayer);
        }

        return minMaxAbsorption;
    }

    private double findLowestAliveHealth(ServerPlayer exclude) {
        double lowest = Double.MAX_VALUE;
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (exclude != null && player.getUUID().equals(exclude.getUUID())) {
                    continue;
                }
                if (player.isDeadOrDying()) {
                    continue;
                }

                double health = player.getHealth();
                if (health > 0.0D && health < lowest) {
                    lowest = health;
                }
            }
        }

        return lowest == Double.MAX_VALUE ? -1.0D : lowest;
    }

    private double findLowestAliveAbsorption(ServerPlayer exclude) {
        double lowest = Double.MAX_VALUE;
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (exclude != null && player.getUUID().equals(exclude.getUUID())) {
                    continue;
                }
                if (player.isDeadOrDying()) {
                    continue;
                }

                double absorption = player.getAbsorptionAmount();
                if (absorption < lowest) {
                    lowest = absorption;
                }
            }
        }

        return lowest == Double.MAX_VALUE ? -1.0D : lowest;
    }

    private ServerPlayer findPlayerWithLowestFood(ServerPlayer exclude) {
        if (server == null) {
            return null;
        }

        ServerPlayer lowest = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (exclude != null && player.getUUID().equals(exclude.getUUID())) {
                continue;
            }
            if (player.isDeadOrDying()) {
                continue;
            }
            if (lowest == null || player.getFoodData().getFoodLevel() < lowest.getFoodData().getFoodLevel()) {
                lowest = player;
            }
        }

        return lowest;
    }

    private void applySharedEffectsToPlayer(ServerPlayer player) {
        Map<Holder<MobEffect>, EffectSnapshot> currentEffects = captureEffects(player);
        for (Holder<MobEffect> type : currentEffects.keySet()) {
            if (!sharedEffects.containsKey(type)) {
                player.removeEffect(type);
            }
        }

        for (Map.Entry<Holder<MobEffect>, EffectSnapshot> entry : sharedEffects.entrySet()) {
            applySharedEffectToPlayer(player, entry.getKey(), entry.getValue());
        }
    }

    private void applySharedEffectToPlayer(ServerPlayer player, Holder<MobEffect> type, EffectSnapshot effect) {
        if (effect == null) {
            player.removeEffect(type);
            return;
        }

        MobEffectInstance desired = effect.toInstance(type);
        MobEffectInstance current = player.getEffect(type);
        if (effect.matches(current)) {
            return;
        }

        player.removeEffect(type);
        player.addEffect(desired);
    }

    private void clampSharedHealthToCurrentCap() {
        if (!data.hasSharedHealth || data.sharedHealth <= 0.0D) {
            return;
        }

        double cap = resolveSharedHealthCap(null);
        if (data.sharedHealth > cap) {
            updateSharedHealth(cap);
            applySharedHealthToAll(cap);
        }
    }

    private void clampSharedAbsorptionToCurrentCap() {
        if (!data.hasSharedAbsorption || data.sharedAbsorption <= 0.0D) {
            return;
        }

        double cap = resolveSharedAbsorptionCap(null);
        if (data.sharedAbsorption > cap) {
            updateSharedAbsorption(cap);
            applySharedAbsorptionToAll();
        }
    }

    private void updateSharedHealth(double value) {
        double clamped = Math.max(0.0D, value);
        if (!data.hasSharedHealth || Math.abs(data.sharedHealth - clamped) > EPSILON) {
            data.dirty = true;
        }

        data.sharedHealth = clamped;
        data.hasSharedHealth = true;
    }

    private void updateSharedAbsorption(double value) {
        double clamped = Math.max(0.0D, value);
        if (!data.hasSharedAbsorption || Math.abs(data.sharedAbsorption - clamped) > EPSILON) {
            data.dirty = true;
        }

        data.sharedAbsorption = clamped;
        data.hasSharedAbsorption = true;
    }

    private void updateSharedHunger(int foodLevel) {
        int clamped = clampFoodLevel(foodLevel);
        if (!data.hasSharedHunger || data.sharedFoodLevel != clamped) {
            data.dirty = true;
        }

        data.sharedFoodLevel = clamped;
        data.hasSharedHunger = true;
    }

    private double clampHealthForPlayer(ServerPlayer player, double health) {
        if (health <= 0.0D) {
            return 0.0D;
        }

        return Math.min(health, resolvePlayerMaxHealth(player));
    }

    private double clampAbsorptionForPlayer(ServerPlayer player, double absorption) {
        if (absorption <= 0.0D) {
            return 0.0D;
        }

        return Math.min(absorption, resolvePlayerMaxAbsorption(player));
    }

    private int clampFoodLevel(int foodLevel) {
        return Math.max(0, Math.min(20, foodLevel));
    }

    private double resolvePlayerMaxHealth(ServerPlayer player) {
        return player.getAttributeValue(Attributes.MAX_HEALTH);
    }

    private double resolvePlayerMaxAbsorption(ServerPlayer player) {
        return Math.max(0.0D, player.getAttributeValue(Attributes.MAX_ABSORPTION));
    }

    private long getPlayerDeathSync(UUID playerId) {
        return data.playerDeathSync.getOrDefault(playerId.toString(), 0L);
    }

    private void markPlayerDeathSynced(UUID playerId, long deathCounter) {
        Long previous = data.playerDeathSync.put(playerId.toString(), Math.max(0L, deathCounter));
        if (previous == null || previous.longValue() != deathCounter) {
            data.dirty = true;
        }
    }

    private void refreshSnapshots() {
        snapshots.clear();
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            snapshots.put(player.getUUID(), PlayerSnapshot.capture(player));
        }
    }

    private static Map<Holder<MobEffect>, EffectSnapshot> captureEffects(ServerPlayer player) {
        Map<Holder<MobEffect>, EffectSnapshot> effects = new HashMap<>();
        Collection<MobEffectInstance> activeEffects = player.getActiveEffects();
        for (MobEffectInstance effect : activeEffects) {
            effects.put(effect.getEffect(), EffectSnapshot.from(effect));
        }
        return effects;
    }

    private void autosave(MinecraftServer currentServer) {
        autosaveTicks++;
        if (autosaveTicks >= AUTOSAVE_INTERVAL_TICKS) {
            autosaveTicks = 0;
            saveData();
        }
    }

    private void loadData() {
        data = new SharedHealthData();
        if (dataPath == null || !Files.exists(dataPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(dataPath)) {
            SharedHealthData loaded = GSON.fromJson(reader, SharedHealthData.class);
            if (loaded != null) {
                data = loaded.sanitize();
            }
        } catch (IOException | JsonParseException exception) {
            System.err.println("[Shared Health Fabric] Could not load data: " + exception.getMessage());
        }
    }

    private void saveData() {
        if (dataPath == null || (!data.dirty && Files.exists(dataPath))) {
            return;
        }

        try {
            Files.createDirectories(dataPath.getParent());
            try (Writer writer = Files.newBufferedWriter(dataPath)) {
                GSON.toJson(data.copyForSave(), writer);
            }
            data.dirty = false;
        } catch (IOException exception) {
            System.err.println("[Shared Health Fabric] Could not save data: " + exception.getMessage());
        }
    }

    private record PlayerSnapshot(
            double health,
            double absorption,
            int foodLevel,
            Map<Holder<MobEffect>, EffectSnapshot> effects) {
        static PlayerSnapshot capture(ServerPlayer player) {
            return new PlayerSnapshot(
                    player.getHealth(),
                    player.getAbsorptionAmount(),
                    player.getFoodData().getFoodLevel(),
                    captureEffects(player));
        }

        boolean hasSharedEffectUpdate(PlayerSnapshot current) {
            if (!effects.keySet().equals(current.effects.keySet())) {
                return true;
            }

            for (Map.Entry<Holder<MobEffect>, EffectSnapshot> entry : effects.entrySet()) {
                EffectSnapshot previousEffect = entry.getValue();
                EffectSnapshot currentEffect = current.effects.get(entry.getKey());
                if (!previousEffect.sameKind(currentEffect)) {
                    return true;
                }

                if (currentEffect.duration > previousEffect.duration + 5) {
                    return true;
                }
            }

            return false;
        }
    }

    private record EffectSnapshot(int duration, int amplifier, boolean ambient, boolean visible, boolean showIcon) {
        static EffectSnapshot from(MobEffectInstance effect) {
            if (effect == null) {
                return null;
            }

            return new EffectSnapshot(
                    effect.getDuration(),
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.isVisible(),
                    effect.showIcon());
        }

        static boolean same(EffectSnapshot first, EffectSnapshot second) {
            if (first == null || second == null) {
                return first == second;
            }

            return first.equals(second);
        }

        static EffectSnapshot strongest(EffectSnapshot first, EffectSnapshot second) {
            if (second.amplifier != first.amplifier) {
                return second.amplifier > first.amplifier ? second : first;
            }

            return second.duration > first.duration ? second : first;
        }

        boolean sameKind(EffectSnapshot other) {
            return other != null
                    && amplifier == other.amplifier
                    && ambient == other.ambient
                    && visible == other.visible
                    && showIcon == other.showIcon;
        }

        MobEffectInstance toInstance(Holder<MobEffect> type) {
            return new MobEffectInstance(type, duration, amplifier, ambient, visible, showIcon);
        }

        boolean matches(MobEffectInstance effect) {
            return same(this, from(effect));
        }
    }

    private static final class SharedHealthData {
        boolean hasSharedHealth;
        double sharedHealth = 20.0D;
        boolean hasSharedHunger;
        int sharedFoodLevel = 20;
        boolean hasSharedAbsorption;
        double sharedAbsorption;
        long sharedDeathCounter;
        Map<String, Long> playerDeathSync = new HashMap<>();
        transient boolean dirty;

        SharedHealthData sanitize() {
            sharedHealth = Math.max(0.0D, sharedHealth);
            sharedFoodLevel = Math.max(0, Math.min(20, sharedFoodLevel));
            sharedAbsorption = Math.max(0.0D, sharedAbsorption);
            sharedDeathCounter = Math.max(0L, sharedDeathCounter);
            if (playerDeathSync == null) {
                playerDeathSync = new HashMap<>();
            }
            dirty = false;
            return this;
        }

        SharedHealthData copyForSave() {
            SharedHealthData copy = new SharedHealthData();
            copy.hasSharedHealth = hasSharedHealth;
            copy.sharedHealth = sharedHealth;
            copy.hasSharedHunger = hasSharedHunger;
            copy.sharedFoodLevel = sharedFoodLevel;
            copy.hasSharedAbsorption = hasSharedAbsorption;
            copy.sharedAbsorption = sharedAbsorption;
            copy.sharedDeathCounter = sharedDeathCounter;
            copy.playerDeathSync = new HashMap<>(playerDeathSync);
            return copy;
        }
    }
}
