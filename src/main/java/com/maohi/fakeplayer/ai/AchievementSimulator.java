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
 * M1: 成就模拟器 — 从 VirtualPlayerManager.processHeavyAILogic 拆分
 * 
 * 判定阶梯：
 * L1(1-5m, Lv0+, 90%) | L2(5-10m, Lv3+, 70%) | L3(10-20m, Lv5+, 30%) | L4(20-40m, Lv10+, 8%) | L5(40-80m, Lv15+, 1%)
 */
public final class AchievementSimulator {

	private AchievementSimulator() {} // 工具类

	private static final String[] ADV_SEQUENCE = {
		"story/mine_stone", "story/upgrade_tools", "story/smelt_iron",
		"story/mine_diamond", "nether/obtain_crying_obsidian"
	};

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
		else if (nextIdx == 3 && playtimeMs > TimingConstants.ACHIEVEMENT_TIER4_PLAYTIME && xpLevel >= 10 && roll < 80) success = true;
		else if (nextIdx == 4 && playtimeMs > TimingConstants.ACHIEVEMENT_TIER5_PLAYTIME && xpLevel >= 15 && roll < 10) success = true;

		if (success) {
			String adv = ADV_SEQUENCE[nextIdx];
			personality.unlockedAdvancements.add(adv);
			personality.hasUnlockedThisSession = true;
			markDirty.run();

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
