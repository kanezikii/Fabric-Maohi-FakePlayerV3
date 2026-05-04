package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.VirtualPlayerManager.Personality;
import com.maohi.fakeplayer.network.FakeClientConnection;
import com.maohi.fakeplayer.TimingConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.PlayerAdvancementTracker;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/**
 * 成就模拟器 (V3)
 */
public final class AchievementSimulator {

	private AchievementSimulator() {} // 工具类

	private static final String[] ADV_SEQUENCE = {
		"story/mine_stone", "story/upgrade_tools", "story/smelt_iron",
		"story/mine_diamond", "nether/obtain_crying_obsidian"
	};
	
	private static boolean isDiamondAchievement(String advancementId) {
		return "story/mine_diamond".equals(advancementId)
				|| "minecraft:story/mine_diamond".equals(advancementId);
	}

	private static boolean canGrantDiamondAchievement(VirtualPlayerManager.Personality personality) {
		if (personality == null) return false;
		if (personality.growthPhase != VirtualPlayerManager.GrowthPhase.DIAMOND_AGE) return false;
		return personality.hasMinedDiamondOre;
	}

	/**
	 * 检查并尝试解锁成就
	 * @param server Minecraft 服务器实例
	 * @param p 假人玩家实体
	 * @param personality 假人个性数据
	 * @param playtimeMs 在线时长（毫秒）
	 * @param dataDirtyRef 数据脏标记回调（解锁后需设为 true）
	 */
	public static void tick(MinecraftServer server, ServerPlayerEntity p, Personality personality, long playtimeMs, Runnable markDirty) {
		int nextIdx = -1;
		for (int i = 0; i < ADV_SEQUENCE.length; i++) {
			if (!personality.unlockedAdvancements.contains(ADV_SEQUENCE[i])) {
				nextIdx = i;
				break;
			}
		}

		if (nextIdx == -1) return; // 全部解锁完毕

		int roll = ThreadLocalRandom.current().nextInt(1000);
		boolean success = false;
		int xpLevel = p.experienceLevel;

		if (nextIdx == 0 && playtimeMs > TimingConstants.ACHIEVEMENT_TIER1_PLAYTIME && roll < 900) success = true;
		else if (nextIdx == 1 && playtimeMs > TimingConstants.ACHIEVEMENT_TIER2_PLAYTIME && xpLevel >= 3 && roll < 700) success = true;
		else if (nextIdx == 2 && playtimeMs > TimingConstants.ACHIEVEMENT_TIER3_PLAYTIME && xpLevel >= 5 && roll < 300) success = true;
		else if (nextIdx == 3 && playtimeMs > TimingConstants.ACHIEVEMENT_TIER4_PLAYTIME && xpLevel >= 10 && roll < 80) {
			if (canGrantDiamondAchievement(personality)) {
				success = true;
			}
		}
		else if (nextIdx == 4 && playtimeMs > TimingConstants.ACHIEVEMENT_TIER5_PLAYTIME && xpLevel >= 15 && roll < 10) success = true;

		if (success) {
			String adv = ADV_SEQUENCE[nextIdx];
			// 二次门禁校验（针对 Diamonds!）
			if (isDiamondAchievement(adv) && !canGrantDiamondAchievement(personality)) {
				return;
			}
			personality.unlockedAdvancements.add(adv);
			personality.hasUnlockedThisSession = true;
			markDirty.run();

			// V5.15 特性：成就触发物资升级
			if (adv.equals("story/mine_stone")) {
				// 给几块圆石和一把石镐
				p.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.COBBLESTONE, 3 + ThreadLocalRandom.current().nextInt(5)));
				p.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_PICKAXE, 1));
			}

			// 主线程安全发放荣誉，加入随机延迟增加凌乱美
			int jitterMs = ThreadLocalRandom.current().nextInt(TimingConstants.JITTER_MIN_MS, TimingConstants.JITTER_MAX_MS);
			server.execute(() -> {
				Identifier id = Identifier.of(adv);
				AdvancementEntry entry = server.getAdvancementLoader().get(id);
				if (entry != null) {
					PlayerAdvancementTracker tracker = p.getAdvancementTracker();
					FakeClientConnection.KEEP_ALIVE_POOL.schedule(() -> {
						server.execute(() -> {
							for (String criterion : entry.value().criteria().keySet()) {
								tracker.grantCriterion(entry, criterion);
							}
						});
					}, jitterMs, java.util.concurrent.TimeUnit.MILLISECONDS);
				}
			});
		}
	}
}
