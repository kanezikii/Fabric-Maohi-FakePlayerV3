package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 成就触发器注册表与调度器 (V5.22 新增)
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ 阶段 × Trigger 映射表                                              │
 * │                                                                    │
 * │ WOOD_AGE    │ PlantSeed, SleepInBed, KillMob, TameAnimal           │
 * │ STONE_AGE   │ PlantSeed, SleepInBed, KillMob, TameAnimal           │
 * │ IRON_AGE    │ PlantSeed, SleepInBed, KillMob, TameAnimal,          │
 * │             │ HotStuff, BreedAnimals(6 种), AdventuringTime,        │
 * │             │ ShootArrow, OlBetsy, SniperDuel, DeflectArrow        │
 * │ DIAMOND_AGE │ BreedAnimals, AdventuringTime, FormObsidian[占位],   │
 * │             │ TameAnimal, ShootArrow, OlBetsy, SniperDuel,         │
 * │             │ DeflectArrow                                          │
 * │ NETHER      │ AdventuringTime, EyeSpy, BlazeRod[占位],             │
 * │             │ ShootArrow, DistractPiglin, OlBetsy, DeflectArrow,   │
 * │             │ CureZombieVillager                                    │
 * │ ENDGAME     │ AdventuringTime, EnchantItem[占位],                  │
 * │             │ ShootArrow, DistractPiglin, OlBetsy, DeflectArrow,   │
 * │             │ CureZombieVillager                                    │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * V5.51-V5.52 扩展:
 *   - ShootArrow      (adventure/shoot_arrow)        — 弓射弱怪
 *   - DistractPiglin  (nether/distract_piglin)       — 下界给猪灵金锭
 *   - TameAnimal      (husbandry/tame_an_animal)     — 骨头驯狼 / 鱼驯猫
 *   - OlBetsy         (adventure/ol_betsy)           — 弩 charge,复用 isUsingBow 状态机
 *   - SniperDuel      (adventure/sniper_duel)        — 50+ 格弓杀骷髅
 *   - DeflectArrow    (story/deflect_arrow)          — 持盾 + 附近骷髅时举盾(V5.52)
 *   - CureZombieVillager (story/cure_zombie_villager) — 投药+喂金苹果动作链(V5.52,跨阶段假设)
 *   - BreedAnimals 扩展从 3 种(cow/chicken/pig)→ 6 种(+sheep/goat/mooshroom)
 *   每个 trigger 自带 shouldRun 兜底(已解锁/资源不足时直接 false),不占运行成本。
 *
 * 错峰机制(解决"真人也不会同时做同一件事"):
 *   1. 每个假人在 Personality 里有独立 triggerPhaseSeed(ThreadLocalRandom 生成)
 *   2. 第一次调度时为每对 (假人 × trigger) 用 phaseSeed + trigger.advancementId().hashCode()
 *      生成一个初始偏移,把 nextIntervalRange() 窗口内的一个随机点作为首次检查时间
 *   3. 每次 tryTrigger 调用完再按 nextIntervalRange 重排下次时间
 *   4. 结果:8 个假人同一秒上线,FormObsidian 不会都挤在第 60 秒 roll——会散在 60~240 秒内
 *
 * 阶段分桶好处:
 *   - WOOD_AGE/STONE_AGE 假人不会浪费 tick 去检查"扔末影之眼",早期 phase 性能更好
 *   - 后期假人不会反复 roll 已解锁的基础成就(Trigger.shouldRun 里也有 alreadyUnlocked 双保险)
 *
 * 扩展方式:
 *   - 新增 trigger:写文件 + 加到 PHASE_BUCKETS 对应阶段的 List
 *   - 已解锁永久跳过:在 Trigger.shouldRun 里 short-circuit alreadyUnlocked
 *   - 改触发频率:只改 Trigger.nextIntervalRange(),不动 Registry
 */
public final class TriggerRegistry {

	private TriggerRegistry() {} // 工具类

	// ===== 阶段分桶:每个 GrowthPhase 只 tick 自己桶里的 trigger =====
	// V5.44: WOOD_AGE 与 STONE_AGE 共享同一份早期 trigger 列表(指向同一 List 实例,无额外内存)
	// V5.51: TameAnimal 也算早期可触发 — 骨头/鱼从骷髅/钓鱼掉,不需高阶资源
	// V5.72: BreedAnimals 下放到早期桶 — 真人前几小时常繁殖。其 tryTrigger 自带"饲料 ≥2 才动"
	//   兜底(无饲料时只做 6 次背包扫描即返回,不扫实体不移动),且移动仅限 8 格内走向动物,
	//   不会引入 spawn drift。与 Phase 3a(破草拿麦种)组合后,早期假人能用麦种繁殖鸡。
	//   注意:AdventuringTime 故意不下放 — 它的 tryLongDistanceTrip 会把假人送到 200~500 格外,
	//   与 V5.62~5.64 的 spawn 牵引(explorationRadius=200,专为早期假人防 chunk gen 卡顿)冲突;
	//   且 adventuring_time 需 ~40 群系,近 spawn 牵引下早期根本凑不齐,下放收益极低。
	private static final List<AchievementTrigger> EARLY_GAME_TRIGGERS = List.of(
		PlantSeedTrigger.INSTANCE,
		SleepInBedTrigger.INSTANCE,
		KillMobTrigger.INSTANCE,
		TameAnimalTrigger.INSTANCE,
		BreedAnimalsTrigger.INSTANCE
	);

	private static final Map<GrowthPhase, List<AchievementTrigger>> PHASE_BUCKETS = Map.of(
		GrowthPhase.WOOD_AGE,  EARLY_GAME_TRIGGERS,
		GrowthPhase.STONE_AGE, EARLY_GAME_TRIGGERS,
		GrowthPhase.IRON_AGE, List.of(
			PlantSeedTrigger.INSTANCE,
			SleepInBedTrigger.INSTANCE,
			KillMobTrigger.INSTANCE,
			HotStuffTrigger.INSTANCE,
			BreedAnimalsTrigger.INSTANCE,
			AdventuringTimeTrigger.INSTANCE,
			TameAnimalTrigger.INSTANCE,
			ShootArrowTrigger.INSTANCE,
			OlBetsyTrigger.INSTANCE,
			SniperDuelTrigger.INSTANCE,
			DeflectArrowTrigger.INSTANCE
		),
		GrowthPhase.DIAMOND_AGE, List.of(
			BreedAnimalsTrigger.INSTANCE,
			AdventuringTimeTrigger.INSTANCE,
			FormObsidianTrigger.INSTANCE,
			TameAnimalTrigger.INSTANCE,
			ShootArrowTrigger.INSTANCE,
			OlBetsyTrigger.INSTANCE,
			SniperDuelTrigger.INSTANCE,
			DeflectArrowTrigger.INSTANCE
		),
		GrowthPhase.NETHER, List.of(
			AdventuringTimeTrigger.INSTANCE,
			EyeSpyTrigger.INSTANCE,
			BlazeRodTrigger.INSTANCE,
			ShootArrowTrigger.INSTANCE,
			DistractPiglinTrigger.INSTANCE,
			OlBetsyTrigger.INSTANCE,
			DeflectArrowTrigger.INSTANCE,
			CureZombieVillagerTrigger.INSTANCE
		),
		GrowthPhase.ENDGAME, List.of(
			AdventuringTimeTrigger.INSTANCE,
			EnchantItemTrigger.INSTANCE,
			ShootArrowTrigger.INSTANCE,
			DistractPiglinTrigger.INSTANCE,
			OlBetsyTrigger.INSTANCE,
			DeflectArrowTrigger.INSTANCE,
			CureZombieVillagerTrigger.INSTANCE
		)
	);

	/**
	 * 每个假人每个 tick 调一次。由 VPM.tickSurvivalAndProgression 挂接。
	 * 本方法 O(当前阶段桶内 trigger 数量),早期假人只会遍历 3 个。
	 */
	public static void tickAll(ServerPlayerEntity player, Personality personality) {
		if (personality == null || personality.growthPhase == null) return;
		List<AchievementTrigger> bucket = PHASE_BUCKETS.get(personality.growthPhase);
		if (bucket == null) return;

		long now = System.currentTimeMillis();
		for (AchievementTrigger t : bucket) {
			String advId = t.advancementId();
			Long nextAt = personality.nextTriggerCheckAt.get(advId);

			// 首次调度:用 phaseSeed + advId.hashCode() 错峰,避免整批假人同时 roll
			if (nextAt == null) {
				personality.nextTriggerCheckAt.put(advId, now + initialOffsetMs(personality, t));
				continue;
			}
			if (now < nextAt) continue;

			// 先排下次,再跑逻辑——即使 tryTrigger 抛异常也不会卡死后续
			personality.nextTriggerCheckAt.put(advId, now + sampleInterval(t));

			if (!t.shouldRun(player, personality)) continue;
			try {
				// V5.50: tryTrigger 返回 true = bot 完整执行了动作链 → 调 broadcastVanillaGrant
				//   触发 vanilla 自动广播 "<player> has made the advancement [<title>]"。
				//   返回 false(前置不满足/没物品/赶路中)不 grant 不广播,语义清晰。
				if (t.tryTrigger(player, personality)) {
					com.maohi.fakeplayer.ai.AchievementSimulator.broadcastVanillaGrant(player, t.advancementId());
				}
			} catch (Throwable ignored) {
				// 安静失败,保持 AI 线程稳定
			}
		}
	}

	/**
	 * 首次调度偏移:用 phaseSeed + advId.hashCode() 生成 [min, max] 内的确定性随机点。
	 * 这样"Alice 的 HotStuff 首检"和"Bob 的 HotStuff 首检"几乎不会落在同一秒。
	 */
	private static long initialOffsetMs(Personality personality, AchievementTrigger t) {
		long[] r = t.nextIntervalRange();
		long span = Math.max(1L, r[1] - r[0]);
		// 混入 advId.hashCode() 保证同一假人的不同 trigger 也错峰
		long mixed = personality.triggerPhaseSeed ^ ((long) t.advancementId().hashCode() * 0x9E3779B97F4A7C15L);
		long offset = Math.floorMod(mixed, span);
		return r[0] + offset;
	}

	/**
	 * 后续调度:每次 tryTrigger 后从 [min, max] 均匀采样。
	 * 用 ThreadLocalRandom 而不是 phaseSeed——第一次已经错峰,后续各走各的更自然。
	 */
	private static long sampleInterval(AchievementTrigger t) {
		long[] r = t.nextIntervalRange();
		long span = Math.max(1L, r[1] - r[0]);
		return r[0] + ThreadLocalRandom.current().nextLong(span);
	}
}
