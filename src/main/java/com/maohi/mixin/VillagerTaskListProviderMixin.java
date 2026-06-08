package com.maohi.mixin;

import com.maohi.fakeplayer.ai.PathfindingNavigation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.brain.task.VillagerTaskListProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * V5.90 收口村民「找床」任务里逐格扫描的阻塞式 getBlockState —— V5.72 {@link WorldMixin}
 * 的姊妹补丁:同一类问题(主线程 getBlockState → getChunkBlocking park),不同 vanilla 调用方。
 *
 * <h3>背景(watchdog 实锤)</h3>
 * 2026-06-08 watchdog 连抓 1.0~1.8s 主线程 park + "Can't keep up 9032ms behind",堆栈一致:
 * <pre>
 * ServerWorld.tickEntity → VillagerEntity.tick → ...mobTick → Brain.tick
 *   → 任务框架(class_7894 / class_7898$1.trigger) → 找床任务(class_4096).sense
 *     → VillagerTaskListProvider.isUnoccupiedBedAt(ServerWorld,BlockPos):72   // class_4129.method_65947
 *       → ServerWorld.getBlockState(候选床位):  // 查 BedBlock.OCCUPIED
 *         → ServerChunkManager.getChunkBlocking:1825   // 床位 chunk 未就绪 → 同步阻塞加载
 *           → MainThreadExecutor.runTasks → Unsafe.park   // C2ME 异步管线铺满 → park 1~1.8s
 * </pre>
 *
 * <h3>触发链(V5.87 代价的浮现)</h3>
 * V5.87(见 {@link ServerChunkLoadingManagerMixin})把假人加载范围缩到 ~7x7 FULL,且假人不传播
 * PLAYER 视距票据。一个村民落在假人 3x3 ticking 区里 → 它照常 tick Brain 找床任务,任务沿村庄
 * 扫候选床位,半径越出那 ~7x7 → {@code isUnoccupiedBedAt} 对未加载床位 {@code getBlockState} →
 * 强制同步加载。假人不停探索把 C2ME 异步 worker 铺满,本该 ~1ms 的 getChunkBlocking 放大到 1~1.8s。
 * (真人在场时视距票据会把整村庄载满,vanilla 永不阻塞;假人只载 7x7 才暴露这条路径。)
 *
 * <h3>修法</h3>
 * {@code @Redirect} 拦住 {@code isUnoccupiedBedAt} 里那次 {@code ServerWorld.getBlockState}:
 * <ul>
 *   <li>chunk 已加载 → {@link PathfindingNavigation#safeGetBlockState} 返真实状态,行为零差异。</li>
 *   <li>chunk 未就绪 → safeGetBlockState 返 null → 这里返 {@code AIR}。AIR 不是 BedBlock →
 *       {@code isUnoccupiedBedAt} 自然返 false → 村民认为该床位不可用、跳过 → 主线程<b>绝不进
 *       getChunkBlocking</b>。等该 chunk 真正加载后,下一次找床任务会重新求值。</li>
 * </ul>
 * 语义安全:未加载 chunk 里的床本就不在 tick,跨未加载边界的找床在 vanilla 即是 best-effort;
 * 和平难度的假人服务器村民睡不睡床也无关进度。与 WorldMixin 同款取舍。
 *
 * <h3>静态性 / owner</h3>
 * {@code isUnoccupiedBedAt(ServerWorld, BlockPos)} 是 VillagerTaskListProvider 工具类的<b>静态</b>
 * 方法(只吃入参、无实例态),故 redirect handler 必须 {@code static};被拦的 getBlockState 接收者
 * 是 {@code ServerWorld} 参数,故 {@code @At} target 的 owner 用 {@code ServerWorld}(非 World)。
 *
 * <h3>yarn 1.21.11 兼容性</h3>
 * <ul>
 *   <li>{@code VillagerTaskListProvider#isUnoccupiedBedAt(ServerWorld,BlockPos)Z} — 对应 intermediary
 *       {@code class_4129.method_65947}(linkie + yarn 1.21.11 .mapping 实证)。</li>
 *   <li>同类还有 {@code method_65948}(同签名的另一床位校验,本次堆栈未命中);若日后 watchdog 抓到它,
 *       照此再加一条 {@code @Redirect} 即可。</li>
 *   <li>{@code defaultRequire:1} 下若 yarn 改名 / 本方法非 static / getBlockState 接收者实为 World →
 *       mixin 加载期硬失败(target not found / static 不符)。届时按上面注释调 owner 或去 static,
 *       或把 "VillagerTaskListProviderMixin" 从 maohi.mixins.json 移除降级回 vanilla。</li>
 * </ul>
 */
@Mixin(VillagerTaskListProvider.class)
public abstract class VillagerTaskListProviderMixin {

    @Redirect(
        method = "isUnoccupiedBedAt(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
        )
    )
    private static BlockState maohi$nonBlockingBedScan(ServerWorld self, BlockPos pos) {
        BlockState state = PathfindingNavigation.safeGetBlockState(self, pos);
        // null = 该床位 chunk 未 FULL → 返 AIR(非 BedBlock)→ isUnoccupiedBedAt 返 false → 村民跳过,不 park。
        return state != null ? state : Blocks.AIR.getDefaultState();
    }
}
