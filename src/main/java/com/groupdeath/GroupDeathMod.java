package com.groupdeath;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

public class GroupDeathMod implements ModInitializer {

    public static final String MOD_ID = "groupdeath";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Server-wide counter, bumped every time a group death is triggered.
    private static final AttachmentType<Integer> GROUP_DEATH_EVENT_ID = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(MOD_ID, "group_death_event_id"), Codec.INT);

    // Per-player marker of the last group death event a player was made to suffer (or was exempt from).
    private static final AttachmentType<Integer> LAST_SEEN_GROUP_DEATH_EVENT_ID = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(MOD_ID, "last_seen_group_death_event_id"), Codec.INT);

    // Text of the death message that triggered the most recent group death, replayed to players who catch up later.
    private static final AttachmentType<String> LAST_GROUP_DEATH_MESSAGE = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(MOD_ID, "last_group_death_message"), Codec.STRING);

    // Guards against re-entering the handler when our own kill() calls fire AFTER_DEATH again.
    private static boolean processingGroupDeath = false;

    // Players whose catch-up kill is waiting on their client to finish loading.
    private static final Set<UUID> pendingCatchUpKills = new HashSet<>();

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
                LOGGER.info("Group death #{} triggered by {}", eventId, dead.getGameProfile().name());

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
                        LOGGER.info("Group death #{}: killed online player {}", eventId, other.getGameProfile().name());
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

        // Players who were offline during a group death missed their kill; catch them up when they reconnect.
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            ServerPlayer player = listener.player;
            int eventId = server.globalAttachments().getAttachedOrElse(GROUP_DEATH_EVENT_ID, 0);
            String name = player.getGameProfile().name();

            if (player.hasAttached(LAST_SEEN_GROUP_DEATH_EVENT_ID)) {
                int lastSeen = player.getAttachedOrThrow(LAST_SEEN_GROUP_DEATH_EVENT_ID);
                LOGGER.info("{} joined; last seen group death #{}, current #{}", name, lastSeen, eventId);
                if (lastSeen < eventId && !player.isSpectator()) {
                    LOGGER.info("{} missed group death(s) up to #{} while offline; queuing catch-up kill until client finishes loading", name, eventId);
                    pendingCatchUpKills.add(player.getUUID());
                }
            } else {
                LOGGER.info("{} joined with no prior group-death record; baselining to #{}", name, eventId);
            }

            player.setAttached(LAST_SEEN_GROUP_DEATH_EVENT_ID, eventId);
        });

        // A player just past JOIN hasn't finished loading their client yet; the server treats them as
        // invulnerable to all damage until connection.hasClientLoaded() is true, so kill() would silently
        // no-op if called immediately. Poll until the client is actually ready.
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
                String name = player.getGameProfile().name();
                if (player.isAlive() && !player.isSpectator()) {
                    GameRules gameRules = server.getGameRules();
                    boolean showDeathMessages = gameRules.get(GameRules.SHOW_DEATH_MESSAGES);
                    String originalMessage = server.globalAttachments().getAttachedOrElse(LAST_GROUP_DEATH_MESSAGE, null);
                    boolean replayMessage = showDeathMessages && originalMessage != null;
                    if (replayMessage) {
                        gameRules.set(GameRules.SHOW_DEATH_MESSAGES, false, null);
                    }
                    // Prevent this kill from re-triggering AFTER_DEATH and cascading into a fresh group death.
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
                    LOGGER.info("Catch-up kill applied to {}", name);
                } else {
                    LOGGER.info("Skipped catch-up kill for {} (no longer alive/valid target)", name);
                }
            }
        });

        LOGGER.info("GroupDeath initialised.");
    }
}
