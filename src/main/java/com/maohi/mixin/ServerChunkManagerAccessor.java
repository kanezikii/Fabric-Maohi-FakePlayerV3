package com.maohi.mixin;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * V5.59 暴露 ServerChunkManager.getChunkHolder(long) 给项目代码,用于实现真正非阻塞的 chunk 访问。
 *
 * <h3>背景</h3>
 * vanilla {@code ServerChunkManager.getChunk(x, z, FULL, false)} 在主线程调用时,即便 {@code create=false}
 * 也会进入 "slow path":未命中 4-slot 缓存即调 {@code mainThreadExecutor.runTasks(future::isDone)} 强制
 * 帮 vanilla 跑队列里所有积压的 chunk gen 任务直到目标就绪 → 主线程 park 等待。
 *
 * <p>项目 watchdog 已抓到证据(2026-05-24 stack):
 * <pre>
 * Unsafe.park → LockSupport.parkNanos → class_1255.method_20813 → class_3215.method_12121
 *   ↑ getChunk(FULL, false) 在主线程上 park,等 vanilla 队列里所有 chunk task 完成
 * </pre>
 *
 * <h3>修法</h3>
 * 通过 {@code getChunkHolder(long)} 直接访问 ChunkHolder,然后调 {@link ChunkHolder#getWorldChunk()} 拿
 * 当前已 FULL 状态的 WorldChunk(返 null 即未就绪)。整条路径无 task pump,无 park。
 *
 * <h3>yarn 1.21.11 映射</h3>
 * - {@code net.minecraft.server.world.ServerChunkManager} 稳定存在(class_3215)
 * - {@code getChunkHolder(long)} 是 yarn 1.21.x 系列的标准命名,返回 {@code ChunkHolder} 或 null
 * - 若未来 yarn 改名 → mixin loader 报 "target method not found",此时本 mixin 移除即可
 *   降级回 getChunk(FULL, false) 旧路径(功能不受影响,只是优化失效)
 */
@Mixin(ServerChunkManager.class)
public interface ServerChunkManagerAccessor {

    /**
     * 按 ChunkPos.toLong 编码 key 取 ChunkHolder。
     * 不存在(chunk 从未被加载/已卸载)返 null。
     * 关键:此方法是 O(1) 的 long2ObjectMap 查询,不调用 runTasks,不会 park 主线程。
     */
    @Invoker("getChunkHolder")
    @Nullable
    ChunkHolder maohi$getChunkHolder(long chunkPos);
}
