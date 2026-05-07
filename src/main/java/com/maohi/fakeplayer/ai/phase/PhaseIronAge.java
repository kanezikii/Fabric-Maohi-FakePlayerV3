package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第二阶段：铁器时代 (V3)
 *
 * 目标：获取铁矿、制作铁器全套，为钻石时代做准备
 * 进入条件：背包有石器（石镐/石剑/石斧）
 * 毕业条件：背包拥有钻石镐或钻石剑
 *
 * 任务优先级：
 *   1. 挖矿为主（找铁矿/煤矿，走矿石层 Y=8~15）55%
 *   2. 砍树补充木材 20%
 *   3. 打猎获取食物/经验 15%
 *   4. 探索 10%
 *
 * V5.28.6 P2-Scan 流程更新:
 *   - 近 24 格扫矿/32 格扫树/20 格扫野怪(在 VirtualPlayerManager.PhaseContext 配置)
 *   - 扫不到 → 切 EXPLORING 走 ±48 格找资源,而不是给假目标(原代码兜底脚下 ±5 down 1~3
 *     的随机点 → 假人挖到泥土/煤层中间空气堵在原地)
 *
 * 待完善：
 *   - 找熔炉/制作熔炉冶炼铁锭
 *   - 制作铁器三件套
 *   - 建造简易基地
 */
public final class PhaseIronAge implements Phase {

    public static final Phase INSTANCE = new PhaseIronAge();

    private PhaseIronAge() {}

    /** V5.28.6 P2-Scan: 铁器时代探索半径 */
    private static final int EXPLORE_RADIUS = 48;

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        int roll = ThreadLocalRandom.current().nextInt(100);

        if (roll < 55) {
            BlockPos target = ctx.findOre.apply(player.getEntityWorld(), player.getBlockPos());
            if (target != null) {
                set(personality, TaskType.MINING, target, TimingConstants.TASK_TIMEOUT_WORK);
            } else {
                // V5.28.6 P2-Scan: 24 格内没矿 → EXPLORING ±48 走出去,下次 tick 重新扫
                setExplore(personality, player);
            }
        } else if (roll < 75) {
            BlockPos target = ctx.findLog.apply(player.getEntityWorld(), player.getBlockPos());
            if (target != null) {
                set(personality, TaskType.WOODCUTTING, target, TimingConstants.TASK_TIMEOUT_WORK);
            } else {
                setExplore(personality, player);
            }
        } else if (roll < 90) {
            net.minecraft.entity.mob.HostileEntity huntTarget = ctx.findHunt.get();
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
                return;
            }
            setExplore(personality, player);
        } else {
            setExplore(personality, player);
        }
    }

    private static void set(Personality p, TaskType type, BlockPos target, long timeout) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = System.currentTimeMillis() + timeout;
    }

    /**
     * V5.28.6 P2-Scan: scan 失败的兜底——派一个 EXPLORING 目标。
     * V5.29 G.3:在面朝方向 ±60° 扇形里采样 EXPLORE_RADIUS 格外的点(0.85~1.0 EXPLORE_RADIUS),
     *   营造"定向跋涉"观感。
     */
    private static void setExplore(Personality p, net.minecraft.server.network.ServerPlayerEntity player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float offsetDeg = rng.nextFloat() * 120f - 60f;            // 朝向 ±60°
        double rad = Math.toRadians(player.getYaw() + offsetDeg);
        double dist = EXPLORE_RADIUS * (0.85 + rng.nextDouble() * 0.15); // 0.85~1.0 半径,贴外圈
        int dx = (int) Math.round(-Math.sin(rad) * dist);
        int dz = (int) Math.round(Math.cos(rad) * dist);
        p.currentTask = TaskType.EXPLORING;
        p.taskTarget = player.getBlockPos().add(dx, 0, dz);
        p.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
    }
}
