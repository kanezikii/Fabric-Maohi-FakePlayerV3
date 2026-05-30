package com.maohi.mixin;

import com.maohi.Maohi;
import com.maohi.fakeplayer.AsyncPlayerSaveService;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.PlayerSaveHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * V5.67 异步写盘核心钩子
 *
 * <p>NOTE: 1.21.11 中 PlayerManager.savePlayerData 只是委托调用
 * PlayerSaveHandler.savePlayerData，实际写盘（NbtIo.writeCompressed）
 * 在 PlayerSaveHandler 内。因此我们必须在 PlayerSaveHandler 上注入，
 * 而不是 PlayerManager。
 *
 * <p>流程：
 * 1. HEAD inject 记录当前正在被保存的玩家到 ThreadLocal。
 * 2. vanilla 在主线程完成 NBT 序列化（writeData → NbtWriteView.getNbt）。
 * 3. @Redirect 拦截 NbtIo.writeCompressed：
 *    - 假人 → 深拷贝 NBT → 提交后台写盘 → 跳过 vanilla 同步 I/O
 *    - 真实玩家 → 降级为 vanilla 同步写盘，保障数据安全
 * 4. RETURN inject 清理 ThreadLocal，防止泄漏。
 */
@Mixin(PlayerSaveHandler.class)
public class PlayerSaveHandlerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MaohiAsyncSave");

    /**
     * ThreadLocal 记录当前正在保存的玩家。
     * PlayerManager.saveAllPlayerData 在主线程顺序逐个调用 savePlayerData，不存在并发竞争。
     */
    private static final ThreadLocal<PlayerEntity> SAVING_PLAYER = new ThreadLocal<>();

    /** Step 1: 记录当前正在保存的玩家 */
    @Inject(method = "savePlayerData", at = @At("HEAD"))
    private void captureSavingPlayer(PlayerEntity player, CallbackInfo ci) {
        SAVING_PLAYER.set(player);
    }

    /**
     * Step 2: 拦截 NbtIo.writeCompressed 写盘调用。
     *
     * <p>此时 vanilla 已在主线程完成 NBT 序列化（nbt 是完整的内存快照），
     * 将假人的磁盘写入提交到后台线程，主线程立即返回，消除 I/O stall。
     *
     * <p>nbt.copy() 生成深拷贝：主线程下一 tick 可能继续修改玩家状态，
     * 后台线程写入的是当前 tick 的完整快照，语义与同步写盘一致。
     */
    @Redirect(
        method = "savePlayerData",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/NbtCompound;Ljava/nio/file/Path;)V"
        )
    )
    private void maohiRedirectWriteCompressed(NbtCompound nbt, Path path) throws IOException {
        PlayerEntity player = SAVING_PLAYER.get();
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();

        if (mgr != null && player != null && mgr.isVirtualPlayer(player.getUuid())) {
            AsyncPlayerSaveService svc = AsyncPlayerSaveService.getInstance();
            if (svc != null) {
                // NOTE: 深拷贝 NBT，防止主线程下一 tick 修改玩家状态影响写盘快照
                NbtCompound snapshot = nbt.copy();
                svc.submit(() -> {
                    try {
                        NbtIo.writeCompressed(snapshot, path);
                    } catch (IOException e) {
                        LOGGER.warn("[MaohiAsyncSave] 假人异步写盘失败 {}: {}",
                            player.getName().getString(), e.getMessage(), e);
                    }
                });
                return; // 跳过 vanilla 同步写盘
            }
        }

        // 真实玩家 或 服务未就绪：降级为 vanilla 同步写盘，数据安全优先
        NbtIo.writeCompressed(nbt, path);
    }

    /** Step 3: 清理 ThreadLocal，防止泄漏 */
    @Inject(method = "savePlayerData", at = @At("RETURN"))
    private void clearSavingPlayer(PlayerEntity player, CallbackInfo ci) {
        SAVING_PLAYER.remove();
    }
}
