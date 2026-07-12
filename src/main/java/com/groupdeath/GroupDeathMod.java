package com.groupdeath;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GroupDeathMod implements ModInitializer {

    public static final String MOD_ID = "groupdeath";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer dead)) return;
            MinecraftServer server = dead.level().getServer();
            if (server == null) return;

            GameRules gameRules = server.getGameRules();
            boolean showDeathMessages = gameRules.get(GameRules.SHOW_DEATH_MESSAGES);
            if (showDeathMessages) {
                gameRules.set(GameRules.SHOW_DEATH_MESSAGES, false, null);
            }
            try {
                for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                    if (other == dead || other.isDeadOrDying() || other.isSpectator()) continue;
                    other.kill(other.level());
                }
            } finally {
                if (showDeathMessages) {
                    gameRules.set(GameRules.SHOW_DEATH_MESSAGES, true, null);
                }
            }
        });

        LOGGER.info("GroupDeath initialised.");
    }
}
