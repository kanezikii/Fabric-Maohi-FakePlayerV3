package com.maohi.mixin;

import com.maohi.Maohi;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.message.MessageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 社交对话感知钩子
 *
 * <p>NOTE: 1.21.11 中 PlayerManager.savePlayerData 委托给 PlayerSaveHandler，
 * 异步写盘逻辑已移至 PlayerSaveHandlerMixin。
 */
@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(
        method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V",
        at = @At("HEAD")
    )
    private void onBroadcast(SignedMessage message, ServerPlayerEntity sender,
                             MessageType.Parameters params, CallbackInfo ci) {
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
        if (mgr != null && sender != null) {
            // 触发假人的对话感知模块
            mgr.onChatMessage(sender, message.getContent().getString());
        }
    }
}
