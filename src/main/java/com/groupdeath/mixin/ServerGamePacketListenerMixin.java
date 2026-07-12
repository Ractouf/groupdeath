package com.groupdeath.mixin;

import com.groupdeath.GroupDeathMod;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void onHandleClientCommand(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN) return;
        if (!GroupDeathMod.FAKE_DEAD.remove(player.getUUID())) return;

        // Restore health display (vanilla client sets it to 0 when showing the death screen)
        player.connection.send(new ClientboundSetHealthPacket(
            player.getHealth(),
            player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel()
        ));

        ci.cancel();
    }
}
