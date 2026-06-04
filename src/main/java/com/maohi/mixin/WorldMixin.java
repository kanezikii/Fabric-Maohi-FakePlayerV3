package com.maohi.mixin;

import com.maohi.fakeplayer.ai.PathfindingNavigation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * V5.72 收口主线程最后一条阻塞式 chunk 加载路径 —— vanilla {@code World.updateComparators}。
 *
 * <h3>背景(watchdog 实锤)</h3>
 * 2026-06-04 watchdog 连续 5 次抓到同一条 1.0~1.8s 主线程 park,堆栈完全一致:
 * <pre>
 * ServerWorld.tick → World.tickBlockEntities → WorldChunk$BlockEntityTickInvoker.tick
 *   → (某方块实体 ticker) → BlockEntity.markDirty(World,BlockPos,BlockState):226
 *     → World.updateComparators(pos, block):887          // 标脏后扫水平邻居找比较器
 *       → World.getBlockState(邻居):7573                  // 读邻居方块状态
 *         → ServerChunkManager.getChunkBlocking:1825      // 邻居 chunk 未就绪 → 同步阻塞加载
 *           → MainThreadExecutor.runTasks → Unsafe.park   // C2ME 异步管线铺满 → park 1~1.8s
 * </pre>
 * 同期 "Can't keep up! Running 6358ms / 7826ms / 8517ms behind"。注意 {@code mspt_throttle_outer}
 * 显示 AI loop 整轮被跳过 —— 所以这卡顿<b>不是 mod 的 AI</b>,是纯 vanilla 方块实体 tick。
 *
 * <h3>触发链</h3>
 * vanilla {@code updateComparators} 扫 4 个水平邻居找比较器,其中"穿过实体方块"的那次
 * <b>距离-2</b> 邻居 {@code getBlockState} 没有 {@code isChunkLoaded} 守卫(距离-1 那次有)。
 * 当标脏的方块实体紧贴 chunk 边界、边界格又是实体方块时,距离-2 就落进<b>未加载的相邻
 * chunk</b> → {@code getBlockState} 内部 {@code getChunk(FULL, create=true)} 强制同步加载 →
 * 主线程 park。bot 不停探索把 C2ME 的异步生成 worker 铺满,本来毫秒级的 getChunkBlocking
 * 被放大到 1~1.8s。
 *
 * <h3>修法</h3>
 * 这是 V5.49 / V5.59 "主线程绝不调 vanilla 阻塞式 getChunk(FULL,true)" 策略的最后一块拼图。
 * mod 自己的调用点早已全部换成 {@link PathfindingNavigation#safeGetBlockState} 的 O(1) 非阻塞
 * 路径,但 {@code updateComparators} 是 vanilla 自己调的,源码层覆盖不到 —— 只能 mixin 收口。
 *
 * <p>{@code @Redirect} 拦住 {@code updateComparators} 里的邻居 {@code getBlockState}:
 * <ul>
 *   <li>距离-1(vanilla 已用 isChunkLoaded 守过):chunk 已 FULL → safeGetBlockState 返真实状态,
 *       行为与原版完全一致,零差异。</li>
 *   <li>距离-2(未守):chunk 未就绪 → 返 {@code AIR} → 不是比较器、不是实体方块 → 该方向自然
 *       跳过 → 主线程<b>绝不触发 getChunkBlocking</b>。</li>
 * </ul>
 *
 * <h3>方向安全性</h3>
 * 返 AIR 仅发生在 chunk 未加载时 —— 未加载的 chunk 本来就不在 tick,里面即便有比较器也不工作;
 * 等它真正加载时,正常的方块更新 / 邻居通知会重新求值。vanilla 自己对距离-1 就是用
 * {@code isChunkLoaded} 这么守的(跨未加载边界的比较器更新在原版即是 best-effort),本 mixin
 * 只是把同一保证补到距离-2。语义安全。
 *
 * <h3>开销</h3>
 * 每次邻居读多一次 {@code instanceof} + O(1) ChunkHolder map 查询(替代 vanilla 内部的 chunk
 * 数组寻址),chunk 已加载的常见情形额外开销 ~几十 ns,相对它消灭的<b>多秒级</b> park 可忽略。
 *
 * <h3>yarn 1.21.11 兼容性</h3>
 * <ul>
 *   <li>{@code net.minecraft.world.World#updateComparators(BlockPos, Block)} — yarn 1.21.x 稳定
 *       (堆栈中 class_1937.method_8455)。</li>
 *   <li>{@code World#getBlockState(BlockPos)} — 堆栈中 class_1937.method_8320,owner 确为 World。</li>
 *   <li>若未来 yarn 改名 → mixin loader 报 "target method not found"(defaultRequire=1 会硬失败,
 *       不会静默 no-op),此时把 "WorldMixin" 从 maohi.mixins.json 移除即可降级回 vanilla 行为。</li>
 * </ul>
 */
@Mixin(World.class)
public abstract class WorldMixin {

    /**
     * 把 updateComparators 内的邻居 getBlockState 改走非阻塞路径。
     *
     * <p>{@code self} 即被调用的 World 实例(== {@code this})。{@code @Redirect} 默认覆盖该方法内
     * <b>所有</b>匹配的 getBlockState 调用点(距离-1 与距离-2 两次读都被收口),正是所需。
     */
    @Redirect(
        method = "updateComparators(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
        )
    )
    private BlockState maohi$nonBlockingComparatorNeighborRead(World self, BlockPos pos) {
        if (self instanceof ServerWorld serverWorld) {
            BlockState state = PathfindingNavigation.safeGetBlockState(serverWorld, pos);
            // null = 该位置 chunk 未 FULL → 返 AIR。AIR 既非比较器也非实体方块,
            //   updateComparators 对该方向直接跳过,主线程不会进入 getChunkBlocking。
            return state != null ? state : Blocks.AIR.getDefaultState();
        }
        // 客户端 / 非 ServerWorld:safeGetBlockState 依赖 ServerChunkManager,不适用 → 回落原版。
        return self.getBlockState(pos);
    }
}
