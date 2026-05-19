package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Oh Shiny: 在下界给非僵尸状态的猪灵一块金锭让它分心 (V5.51 新增)
 *
 * vanilla 路径:PiglinBrain.acceptsForBarter / PiglinEntity.interactMob 检查主手 == gold_ingot
 *            → 进入 admiring 6 秒 → distract_piglin criterion → [Oh Shiny]
 *            (advancement: nether/distract_piglin)
 *
 * 前置:bot 在下界 + 背包有金锭。NETHER 阶段 bot 多半已挖过金/换取金锭(IRON_AGE 之后)。
 *
 * 风险控制:
 *   - 猪灵处于 attacking 状态(玩家偷过箱子/打过它)不会接金锭 → 用 getTarget()==null 过滤敌对状态。
 *   - 不在线 piglin 子类 PiglinBruteEntity 不接金锭,精确 getClass()==PiglinEntity 才扫。
 *   - 没用 isImmuneToZombification 过滤:该方法在 1.21.11 yarn mapping 上未在项目其它处验证过,
 *     避免编译耦合;若猪灵在变僵状态下 interactMob 失败也无伤,trigger 节流后下次 roll。
 */
public final class DistractPiglinTrigger implements AchievementTrigger {

	public static final DistractPiglinTrigger INSTANCE = new DistractPiglinTrigger();
	private static final String ADV_ID = "nether/distract_piglin";

	private DistractPiglinTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{20_000L, 90_000L}; } // 20~90s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 仅在下界 — 主世界没自然 piglin
		if (player.getEntityWorld().getRegistryKey() != World.NETHER) return false;
		return TriggerUtil.hasItem(player.getInventory(), Items.GOLD_INGOT);
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责
		PiglinEntity target = findFriendlyPiglin(player);
		if (target == null) return false;

		double distSq = player.squaredDistanceTo(target);
		// 远了先派路任务走过去,下次 roll 再尝试交互(8 格内)
		if (distSq > 9.0) {
			personality.taskTarget = target.getBlockPos();
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 600; // 30s = 600 ticks
			return false;
		}

		// 切金锭到 hotbar 0(PiglinEntity.interactMob 只看主手物品)
		PlayerInventory inv = player.getInventory();
		int goldSlot = TriggerUtil.findItemSlot(inv, Items.GOLD_INGOT);
		if (goldSlot == -1) return false;
		if (goldSlot >= 9) {
			TriggerUtil.swapToHotbar(player, goldSlot, 0);
			goldSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, goldSlot);

		// 朝猪灵眼睛
		TriggerUtil.facePoint(player, target.getEyePos());

		// vanilla PiglinEntity.interactMob:验主手 gold_ingot → 消耗 1 个 + admire + 触发 criterion
		//   不读 ActionResult 返回值,1.21.x 多次重构避免版本耦合(同 BreedAnimalsTrigger 样板)
		target.interactMob(player, Hand.MAIN_HAND);
		player.swingHand(Hand.MAIN_HAND, true);
		// V5.51: 完整发完交互链 → Registry 据此调 broadcastVanillaGrant 触发广播
		return true;
	}

	/** 8 格内找成年非僵尸非战斗中的 PiglinEntity(精确类型,排除 PiglinBruteEntity 子类) */
	private static PiglinEntity findFriendlyPiglin(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(8.0);
		List<PiglinEntity> piglins = player.getEntityWorld().getEntitiesByClass(
			PiglinEntity.class, box,
			e -> e.isAlive() && !e.isBaby()
				&& e.getClass() == PiglinEntity.class // 排除 PiglinBruteEntity 子类
				&& e.getTarget() == null);            // 没在攻击中
		if (piglins.isEmpty()) return null;
		// 选最近的(Vec3d 距离),距离短一些 interact 包更不会 trip vanilla 的"too far"拒绝
		PiglinEntity best = null;
		double bestSq = Double.MAX_VALUE;
		Vec3d eyePos = player.getEyePos();
		for (PiglinEntity p : piglins) {
			double dsq = p.squaredDistanceTo(eyePos);
			if (dsq < bestSq) { bestSq = dsq; best = p; }
		}
		return best;
	}
}
