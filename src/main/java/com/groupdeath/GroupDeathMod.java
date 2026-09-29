package com.groupdeath;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GroupDeathMod implements ModInitializer {

    public static final String MOD_ID = "groupdeath";

    private static final AttachmentType<Integer> GROUP_DEATH_EVENT_ID = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(MOD_ID, "group_death_event_id"), Codec.INT);

    private static final AttachmentType<Integer> LAST_SEEN_GROUP_DEATH_EVENT_ID = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(MOD_ID, "last_seen_group_death_event_id"), Codec.INT);

    private static final AttachmentType<String> LAST_GROUP_DEATH_MESSAGE = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(MOD_ID, "last_group_death_message"), Codec.STRING);

    public static final GameRule<Boolean> SHARED_HEALTH_POOL = GameRuleBuilder.forBoolean(false)
            .category(GameRuleCategory.PLAYER)
            .buildAndRegister(Identifier.fromNamespaceAndPath(MOD_ID, "shared_health_pool"));

    public static final GameRule<Boolean> MIRROR_DAMAGE = GameRuleBuilder.forBoolean(false)
            .category(GameRuleCategory.PLAYER)
            .buildAndRegister(Identifier.fromNamespaceAndPath(MOD_ID, "mirror_damage"));

    private static boolean processingGroupDeath = false;

    private static boolean processingMirrorDamage = false;

    private static final Set<UUID> pendingCatchUpKills = new HashSet<>();

    private static final Map<UUID, Float> lastKnownHealth = new HashMap<>();

    @Override
    public void onInitialize() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (processingGroupDeath) return;
            if (!(entity instanceof ServerPlayer dead)) return;
            MinecraftServer server = dead.level().getServer();
            if (server == null) return;

            processingGroupDeath = true;
            try {
                int eventId = server.globalAttachments().getAttachedOrElse(GROUP_DEATH_EVENT_ID, 0) + 1;
                server.globalAttachments().setAttached(GROUP_DEATH_EVENT_ID, eventId);

                GameRules gameRules = server.getGameRules();
                boolean showDeathMessages = gameRules.get(GameRules.SHOW_DEATH_MESSAGES);
                Component deathMessage = source.getLocalizedDeathMessage(dead);
                server.globalAttachments().setAttached(LAST_GROUP_DEATH_MESSAGE, deathMessage.getString());
                if (showDeathMessages) {
                    gameRules.set(GameRules.SHOW_DEATH_MESSAGES, false, null);
                }
                try {
                    for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                        other.setAttached(LAST_SEEN_GROUP_DEATH_EVENT_ID, eventId);
                        if (other == dead || other.isDeadOrDying() || other.isSpectator()) continue;
                        other.kill(other.level());
                        if (showDeathMessages) {
                            other.connection.send(new ClientboundPlayerCombatKillPacket(other.getId(), deathMessage));
                        }
                    }
                } finally {
                    if (showDeathMessages) {
                        gameRules.set(GameRules.SHOW_DEATH_MESSAGES, true, null);
                    }
                }
            } finally {
                processingGroupDeath = false;
            }
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer hurt)) return;
            MinecraftServer server = hurt.level().getServer();
            if (server == null) return;
            GameRules gameRules = server.getGameRules();

            if (!processingMirrorDamage && gameRules.get(MIRROR_DAMAGE)) {
                processingMirrorDamage = true;
                try {
                    for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                        if (other == hurt || other.isDeadOrDying() || other.isSpectator()) continue;
                        other.hurtServer(other.level(), source, damageTaken);
                    }
                } finally {
                    processingMirrorDamage = false;
                }
            }
        });

        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            ServerPlayer player = listener.player;
            int eventId = server.globalAttachments().getAttachedOrElse(GROUP_DEATH_EVENT_ID, 0);

            if (player.hasAttached(LAST_SEEN_GROUP_DEATH_EVENT_ID)) {
                int lastSeen = player.getAttachedOrThrow(LAST_SEEN_GROUP_DEATH_EVENT_ID);
                if (lastSeen < eventId && !player.isSpectator()) {
                    pendingCatchUpKills.add(player.getUUID());
                }
            }

            player.setAttached(LAST_SEEN_GROUP_DEATH_EVENT_ID, eventId);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pendingCatchUpKills.isEmpty()) return;

            Iterator<UUID> iterator = pendingCatchUpKills.iterator();
            while (iterator.hasNext()) {
                UUID id = iterator.next();
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) {
                    iterator.remove();
                    continue;
                }
                if (!player.connection.hasClientLoaded()) continue;

                iterator.remove();
                if (player.isAlive() && !player.isSpectator()) {
                    GameRules gameRules = server.getGameRules();
                    boolean showDeathMessages = gameRules.get(GameRules.SHOW_DEATH_MESSAGES);
                    String originalMessage = server.globalAttachments().getAttachedOrElse(LAST_GROUP_DEATH_MESSAGE, null);
                    boolean replayMessage = showDeathMessages && originalMessage != null;
                    if (replayMessage) {
                        gameRules.set(GameRules.SHOW_DEATH_MESSAGES, false, null);
                    }
                    processingGroupDeath = true;
                    try {
                        player.kill(player.level());
                        if (replayMessage) {
                            player.connection.send(new ClientboundPlayerCombatKillPacket(player.getId(), Component.literal(originalMessage)));
                        }
                    } finally {
                        processingGroupDeath = false;
                        if (replayMessage) {
                            gameRules.set(GameRules.SHOW_DEATH_MESSAGES, true, null);
                        }
                    }
                }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!server.getGameRules().get(SHARED_HEALTH_POOL)) {
                if (!lastKnownHealth.isEmpty()) lastKnownHealth.clear();
                return;
            }

            Float newPoolValue = null;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.isDeadOrDying() || player.isSpectator() || player.isCreative()) {
                    lastKnownHealth.remove(player.getUUID());
                    continue;
                }
                float health = player.getHealth();
                Float last = lastKnownHealth.put(player.getUUID(), health);
                if (last != null && last != health) {
                    newPoolValue = health;
                }
            }

            if (newPoolValue == null) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.isDeadOrDying() || player.isSpectator() || player.isCreative()) continue;
                float clamped = Math.min(newPoolValue, player.getMaxHealth());
                if (player.getHealth() != clamped) {
                    if (clamped < player.getHealth()) {
                        player.level().broadcastDamageEvent(player, player.damageSources().generic());
                    }
                    player.setHealth(clamped);
                    lastKnownHealth.put(player.getUUID(), clamped);
                }
            }
        });
    }
}
