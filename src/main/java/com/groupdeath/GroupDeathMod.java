package com.groupdeath;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GroupDeathMod implements ModInitializer {

    public static final String MOD_ID = "groupdeath";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Set<UUID> FAKE_DEAD = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onInitialize() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer dead)) return;
            MinecraftServer server = dead.level().getServer();
            if (server == null) return;

            Component deathMessage = source.getLocalizedDeathMessage(dead);

            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other == dead) continue;
                FAKE_DEAD.add(other.getUUID());
                other.connection.send(new ClientboundPlayerCombatKillPacket(other.getId(), deathMessage));
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            FAKE_DEAD.remove(handler.player.getUUID())
        );

        LOGGER.info("GroupDeath initialised.");
    }
}
