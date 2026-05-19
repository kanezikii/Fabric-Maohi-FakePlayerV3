package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.GrowthPhase;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 成就观察器 (V5.18 - 重构)
 *
 * 设计理念变更（V5.18）：
 *   旧版本（V3-V5.17）会按"在线时长 + 概率"伪造成就广播。但假人现在已经能做真实任务
 *   （挖矿、合成、冶炼、建造传送门），vanilla 的 advancement 系统会基于
 *   inventory_changed / entity_summoned / location 等真实事件自动触发广播——
 *   伪造路径反而会造成"广播了但背包没东西"的穿帮。
 *
 * 新版本（V5.18）只保留两个职责：
 *   1. 把 vanilla 已自动触发的成就同步到 personality.unlockedAdvancements
 *      （供 phase 系统、chat 系统、存档系统读取）
 *   2. 提供静态查询方法，让其它模块判断假人是否已解锁某成就
 *
 * P22 重构(实事求是):
 *   旧 syncFromVanilla 在 ADV_SEQUENCE 上硬编码 6 个 advancement ID 一一查 loader,
 *   "story/mine_wood" 在 1.21.11 vanilla advancement registry 中找不到(log 证据:
 *   sync_entry_null id=story/mine_wood 必中,而 story/mine_stone 等同格式都 OK)。
 *   原因可能是 vanilla 改名 / mod 移除 / loader 加载顺序问题,根因无法 100% 锁定。
 *
 *   现在改为全量扫:不再依赖任何硬编码 ID,直接遍历 loader 所有 entry,
 *   只筛 display.isPresent()(过滤 recipe 类 advancement,只统计真实"成就"),
 *   personality 没记过 + isDone() 的就 sync。不管 1.21.11 把 mine_wood 叫什么,
 *   bot 真触发了 vanilla criterion 就抄写,robust to mapping/rename。
 *   副作用:ach 数字会自动覆盖 vanilla 所有 110+ advancement,而非仅 6 档里程碑。
 */
public final class AchievementSimulator {

	private AchievementSimulator() {} // 工具类

	/**
	 * 5 个里程碑成就 ID,用于 phase 系统 / chat 叙事时索引。
	 * 这些"逻辑 ID"是模糊匹配 key:hasUnlocked 接受 endsWith 命中,
	 * 即 vanilla 真名是 "minecraft:story/mine_wood" 也能匹配 "story/mine_wood" 查询。
	 * 不再用于 syncFromVanilla 硬编码循环。
	 */
	public static final String[] ADV_SEQUENCE = {
		"story/mine_wood",
		"story/mine_stone",
		"story/upgrade_tools",
		"story/smelt_iron",
		"story/mine_diamond",
		"nether/obtain_crying_obsidian"
	};

	/**
	 * P22 重构:全量扫 loader,不再依赖 ADV_SEQUENCE 硬编码 ID。
	 *
	 * 工作流:
	 *   1. 反射拿 loader 中所有 AdvancementEntry(兼容多 yarn build method name)
	 *   2. 筛 display.isPresent() — 过滤 recipe 类 advancement,只统计真成就
	 *   3. 每个 personality 没记过 + isDone() 的都 sync + count + log
	 *
	 * 性能:30s 一次 × ~110 个 advancement display check ≈ 3300 操作/30s/bot,
	 *   远低于挖矿/寻路开销;loader 调用走反射但只在 sync 入口一次,够便宜。
	 *
	 * @return 本次新观察到的成就数量
	 */
	public static int syncFromVanilla(MinecraftServer server, ServerPlayerEntity p, Personality personality) {
		if (server == null || p == null || personality == null) return 0;

		java.util.Collection<AdvancementEntry> allAdvs = enumerateLoader(server);
		int newlyObserved = 0;

		if (allAdvs != null && !allAdvs.isEmpty()) {
			for (AdvancementEntry entry : allAdvs) {
				// V5.54: ID 归一化 — minecraft namespace 剥前缀,与项目其他写入点(directGrant/grantOne/
				//   EquipmentBehavior/SmeltingBehavior/CraftingBehavior)统一裸路径(story/xxx)。
				//   旧版本这里写 "minecraft:story/mine_stone",其他写入点写 "story/mine_stone",
				//   /maohi list 显示 size 双倍,实际同一成就被记两次。mod advancement(非 minecraft
				//   namespace)保留完整 ID,不和项目内部里程碑冲突。
				net.minecraft.util.Identifier advIdObj = entry.id();
				String advId = "minecraft".equals(advIdObj.getNamespace()) ? advIdObj.getPath() : advIdObj.toString();

				// 过滤 recipe 类(display=empty):不统计 "minecraft:recipes/..." 这类自动解锁的非成就 advancement
				if (entry.value().display().isEmpty()) continue;

				if (personality.unlockedAdvancements.contains(advId)) continue;
				if (!p.getAdvancementTracker().getProgress(entry).isDone()) continue;

				personality.unlockedAdvancements.add(advId);
				personality.hasUnlockedThisSession = true;
				newlyObserved++;
				com.maohi.fakeplayer.TaskLogger.log(p, "achievement_unlocked", "id", advId);
				com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(p.getUuid());

				// V5.50.1: vanilla 自身 fire 路径下,理论上 fire 时已经触发 endorse → broadcast,
				//   但实测某些场景下 fake player 路径上的 fire 链路被吞(criteria trigger 调用
				//   不一致),advancement done 但聊天没广播。补一次 broadcastVanillaGrant:
				//     - vanilla 已 fire 且广播完成 → grantCriterion 内部 alreadyDone=true,
				//       endorse 不再 fire,无副作用
				//     - vanilla fire 但广播被吞 → grantCriterion 仍是 no-op(advancement 已 done),
				//       但...实际上这种情况 endorse 永远不会再 fire,本路径补救无效
				//   实际意义:对项目"isDone() 但 endorse 没跑"的边界场景兜底,纯保险写法,
				//   主战场仍是 grantOne / directGrant 路径上的 broadcastVanillaGrant 调用。
				broadcastVanillaGrant(p, advId);
			}
		}

		// P23 行为观察补救:vanilla criterion 对 fake player 不可靠,但 ServerStatHandler 在
		//   onPlayerAction / onPlayerInteractEntity 内部 incrementStat,fake player 走我们的
		//   发包路径同样累积。用 stat 阈值作为"实事求是"补救入口,Set.add 去重保证只记一次。
		newlyObserved += observeStatMilestones(p, personality);
		newlyObserved += observeDimension(p, personality);

		return newlyObserved;
	}

	/**
	 * P23: 用 ServerStatHandler 观察击杀 / 睡床 / 食物等 stat,达阈值即 direct_grant。
	 * 反射拿 Stats 静态字段避免编译期类型耦合(yarn build 间 method/field 漂移)。
	 */
	private static int observeStatMilestones(ServerPlayerEntity p, Personality personality) {
		int newCount = 0;
		try {
			net.minecraft.stat.ServerStatHandler stats = p.getStatHandler();
			if (stats == null) return 0;

			// 击杀任意生物 → adventure/kill_a_mob
			if (!personality.unlockedAdvancements.contains("adventure/kill_a_mob")) {
				int mobKills = stats.getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.MOB_KILLS));
				if (mobKills > 0) newCount += grantOne(p, personality, "adventure/kill_a_mob", "stat:mob_kills");
			}
			// 睡床 → adventure/sleep_in_a_bed
			if (!personality.unlockedAdvancements.contains("adventure/sleep_in_a_bed")) {
				int slept = stats.getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.SLEEP_IN_BED));
				if (slept > 0) newCount += grantOne(p, personality, "adventure/sleep_in_a_bed", "stat:sleep_in_bed");
			}
			// 跳跃过任意次 → 一个非里程碑用作"鲜活度"标记,但 vanilla 没成就对应,跳过
			// 鱼上钩 → husbandry/fishy_business
			if (!personality.unlockedAdvancements.contains("husbandry/fishy_business")) {
				int fish = stats.getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.FISH_CAUGHT));
				if (fish > 0) newCount += grantOne(p, personality, "husbandry/fishy_business", "stat:fish_caught");
			}
			// 击杀 player → adventure/kill_player(PVP 假人 sparring 触发)
			if (!personality.unlockedAdvancements.contains("adventure/kill_player")) {
				int pk = stats.getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.PLAYER_KILLS));
				if (pk > 0) newCount += grantOne(p, personality, "adventure/kill_player", "stat:player_kills");
			}
			// 受伤(被打) → adventure/take_a_hit (非里程碑但 vanilla 有)
			if (!personality.unlockedAdvancements.contains("adventure/take_a_hit")) {
				int dmgTaken = stats.getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DAMAGE_TAKEN));
				if (dmgTaken > 0) newCount += grantOne(p, personality, "adventure/take_a_hit", "stat:damage_taken");
			}
			// 死亡过 → adventure/very_very_frightening (vanilla 没直接 stat-only 成就,用 deaths 作 metric 观测)
			if (!personality.unlockedAdvancements.contains("end/respawn_anchor")) {
				// 占位:end 系列实际需要专门触发,跳过
			}
			// 食物 use 次数总和(任意食物吃过 → husbandry/eat_meat 系列粗略合并)
			if (!personality.unlockedAdvancements.contains("husbandry/balanced_diet")) {
				// USED stat 是 per-item,我们只挑常见食物 sum,>0 即视为 bot 已吃过东西
				int eaten = 0;
				net.minecraft.item.Item[] commonFoods = {
					net.minecraft.item.Items.BREAD, net.minecraft.item.Items.APPLE,
					net.minecraft.item.Items.COOKED_BEEF, net.minecraft.item.Items.COOKED_PORKCHOP,
					net.minecraft.item.Items.COOKED_CHICKEN, net.minecraft.item.Items.COOKED_MUTTON,
					net.minecraft.item.Items.COOKED_RABBIT, net.minecraft.item.Items.COOKED_COD,
					net.minecraft.item.Items.COOKED_SALMON, net.minecraft.item.Items.CARROT,
					net.minecraft.item.Items.POTATO, net.minecraft.item.Items.BAKED_POTATO,
					net.minecraft.item.Items.BEETROOT, net.minecraft.item.Items.BEEF,
					net.minecraft.item.Items.PORKCHOP, net.minecraft.item.Items.CHICKEN,
					net.minecraft.item.Items.MUTTON, net.minecraft.item.Items.RABBIT,
					net.minecraft.item.Items.COD, net.minecraft.item.Items.SALMON
				};
				for (net.minecraft.item.Item food : commonFoods) {
					eaten += stats.getStat(net.minecraft.stat.Stats.USED.getOrCreateStat(food));
					if (eaten > 0) break;
				}
				if (eaten > 0) newCount += grantOne(p, personality, "husbandry/eat_food", "stat:food_used");
			}
		} catch (Throwable t) {
			// 防御:某 yarn build stat 字段不存在 → 静默跳过观察,主流程不受影响
			com.maohi.fakeplayer.TaskLogger.log(p, "stat_observe_fail",
				"error", t.getClass().getSimpleName() + ":" + t.getMessage());
		}
		return newCount;
	}

	/**
	 * P23: 观察 bot 所在维度,进 nether/end 即视为对应里程碑达成。
	 * 每 30s 扫一次性能可控;Set.add 去重,玩家长期在 nether 也只记一次。
	 */
	private static int observeDimension(ServerPlayerEntity p, Personality personality) {
		int newCount = 0;
		try {
			net.minecraft.registry.RegistryKey<net.minecraft.world.World> dim = p.getEntityWorld().getRegistryKey();
			if (dim.equals(net.minecraft.world.World.NETHER)) {
				if (!personality.unlockedAdvancements.contains("story/enter_the_nether")) {
					newCount += grantOne(p, personality, "story/enter_the_nether", "dim:nether");
				}
			} else if (dim.equals(net.minecraft.world.World.END)) {
				if (!personality.unlockedAdvancements.contains("story/enter_the_end")) {
					newCount += grantOne(p, personality, "story/enter_the_end", "dim:end");
				}
			}
		} catch (Throwable t) {
			com.maohi.fakeplayer.TaskLogger.log(p, "dim_observe_fail",
				"error", t.getClass().getSimpleName() + ":" + t.getMessage());
		}
		return newCount;
	}

	/**
	 * P23: 共用 direct_grant 单点入口(Set.add + log + metrics + markDirty)。
	 * 返回 1 表示首次解锁(供 syncFromVanilla 累计 newlyObserved),0 表示重复跳过。
	 *
	 * V5.50: 在自己 Set.add 之后,额外触发 vanilla advancement grant,
	 *   让 server 自动广播 "<player> has made the advancement [<title>]" —— 拟真初衷的核心。
	 *   旧版本因 P23 时期"vanilla criterion 对 fake player 不可靠"而绕开,但那是在
	 *   player.networkHandler 还未完成 PLAY state 的早期。spawn 完成后,
	 *   getAdvancementTracker().grantCriterion 应能正常工作并触发 PlayerManager.broadcast。
	 */
	private static int grantOne(ServerPlayerEntity p, Personality personality, String advId, String trigger) {
		if (!personality.unlockedAdvancements.add(advId)) return 0;
		personality.hasUnlockedThisSession = true;
		com.maohi.fakeplayer.TaskLogger.log(p, "achievement_unlocked",
			"id", advId, "via", "behavior_observe", "trigger", trigger);
		com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(p.getUuid());
		com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
		if (mgr != null) mgr.markStorageDirty();

		// V5.50: 真触发 vanilla advancement,让 server 自动派发 chat 广播
		broadcastVanillaGrant(p, advId);

		return 1;
	}

	/**
	 * V5.50: 把项目自己的"逻辑成就 ID"映射到 vanilla AdvancementEntry,
	 *   调用 grantCriterion 触发 vanilla 内部的 endorse() → PlayerManager.broadcast 广播链路。
	 *
	 *   公开入口供 grantOne / CraftingBehavior / SmeltingBehavior / VPM directGrant 等所有
	 *   解锁路径在 add Set 之后调用一次,确保所有成就解锁都伴随 vanilla 广播。
	 *
	 * V5.50.1: 返回值改为 boolean,区分 vanilla 真实认识的 advancement vs 项目自定义里程碑。
	 *   - true  = vanilla loader 找到 entry,已调 grantCriterion 触发广播
	 *   - false = vanilla loader 找不到(项目自定义 ID 如 story/obtain_coal),静默跳过
	 *   调用方据此决定是否记入 personality.unlockedAdvancements:
	 *     真实 vanilla advancement → 记 Set + log + metrics(/maohi list 计数与真人一致)
	 *     项目自定义里程碑       → 仍可单独记 metrics 但不污染 Set 计数
	 *
	 * 设计要点:
	 *   1. ID 归一化:剥掉可能的 namespace 前缀,统一走 minecraft:&lt;path&gt;
	 *   2. loader 找不到 entry 静默返回 false(向后兼容自定义 advancement / 跨版本 rename)
	 *   3. 给 advancement 的**所有** criterion 打钩 —— grantCriterion 内部检查完成度,
	 *      全 done 时 fire endorse() → 广播;若已 done 则是 no-op
	 *   4. 任何异常吞掉只 log,绝不影响调用方逻辑
	 *
	 * announceAdvancements gamerule:
	 *   服主若把这个 gamerule 设为 false,vanilla 内部 broadcast 会被抑制 —— 这是 vanilla 行为,
	 *   不在本方法控制范围。默认值 true,大多数服都广播。
	 *
	 * @return true 如果 advId 是 vanilla 真实 advancement 且 grant 成功;false 否则
	 */
	public static boolean broadcastVanillaGrant(ServerPlayerEntity p, String advId) {
		try {
			MinecraftServer server = p.getEntityWorld().getServer();
			if (server == null) return false;
			// 归一化 ID:剥掉可能的 "minecraft:" 前缀,统一用 minecraft 命名空间。
			//   不接 mod 自定义 advancement —— 项目里 stat-driven 路径 ID 全是 vanilla 路径。
			String path = advId.contains(":") ? advId.substring(advId.indexOf(':') + 1) : advId;
			Identifier id = Identifier.of("minecraft", path);
			AdvancementEntry entry = server.getAdvancementLoader().get(id);
			if (entry == null) {
				com.maohi.fakeplayer.TaskLogger.log(p, "vanilla_grant_skip",
					"id", advId, "reason", "loader_no_entry");
				return false;
			}
			// 给所有 criterion 打钩:grantCriterion 内部会检测全 done,fire endorse() 广播;
			//   若某 criterion 已 done,grantCriterion 早返 false,无副作用。
			for (String criterion : entry.value().criteria().keySet()) {
				p.getAdvancementTracker().grantCriterion(entry, criterion);
			}
			return true;
		} catch (Throwable t) {
			com.maohi.fakeplayer.TaskLogger.log(p, "vanilla_grant_fail",
				"id", advId, "err", t.getClass().getSimpleName() + ":" + t.getMessage());
			return false;
		}
	}

	/**
	 * P22 公开入口:让 VPM P11 grant 路径复用同一份反射枚举逻辑。
	 * 内部委派 enumerateLoader,private 实现不变。
	 */
	public static java.util.Collection<AdvancementEntry> enumerateLoaderPublic(MinecraftServer server) {
		return enumerateLoader(server);
	}

	// 一次性诊断 dump 节流:第一次 enumerateLoader 被调用时,输出真实路径 + 头 20 个 advancement id,
	//   一行就够,之后跑无关 ach 业务时静默。
	private static final java.util.concurrent.atomic.AtomicBoolean ENUMERATE_DUMPED =
		new java.util.concurrent.atomic.AtomicBoolean(false);

	/**
	 * 反射 + cast 拿 loader 所有 advancement,兼容多 yarn build。
	 *
	 * P22 三段兜底:
	 *   1) method reflection: getAdvancements / getAll / values / entries / iterator
	 *   2) field reflection: 找 loader 内部 Map<Identifier, AdvancementEntry> 字段,取 values()
	 *   3) ADV_SEQUENCE hardcoded fallback(只剩 5 档 milestone,不含 mine_wood)
	 *
	 * 配合一次性 dump 输出真实走的是哪条路径 + loader 内含哪些 ID。
	 */
	@SuppressWarnings("unchecked")
	private static java.util.Collection<AdvancementEntry> enumerateLoader(MinecraftServer server) {
		Object loader = server.getAdvancementLoader();
		String loaderClassName = loader.getClass().getName();
		java.util.Collection<AdvancementEntry> result = null;
		String diagPath = "none";
		String diagErr = null;

		// 1) method reflection
		for (String m : new String[]{"getAdvancements", "getAll", "values", "entries", "iterator", "getAdvancementEntries"}) {
			try {
				java.lang.reflect.Method method = loader.getClass().getMethod(m);
				Object res = method.invoke(loader);
				if (res instanceof java.util.Collection<?> c && !c.isEmpty()) {
					// 验证 element 类型 — 防止 method 同名但返回 Map.entrySet 等
					Object first = c.iterator().next();
					if (first instanceof AdvancementEntry) {
						result = (java.util.Collection<AdvancementEntry>) c;
						diagPath = "method:" + m;
						break;
					}
				}
				if (res instanceof Iterable<?> it) {
					java.util.List<AdvancementEntry> list = new java.util.ArrayList<>();
					for (Object o : it) {
						if (o instanceof AdvancementEntry e) list.add(e);
					}
					if (!list.isEmpty()) {
						result = list;
						diagPath = "method-iter:" + m;
						break;
					}
				}
			} catch (NoSuchMethodException ignored) {
			} catch (Throwable t) {
				diagErr = m + "→" + t.getClass().getSimpleName();
			}
		}

		// 2) field reflection 兜底:在 loader 类及其父类里找 Map<Identifier, AdvancementEntry> 字段
		if (result == null) {
			Class<?> klass = loader.getClass();
			outer:
			while (klass != null && klass != Object.class) {
				for (java.lang.reflect.Field f : klass.getDeclaredFields()) {
					if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
					try {
						f.setAccessible(true);
						Object obj = f.get(loader);
						if (!(obj instanceof java.util.Map<?, ?> m)) continue;
						if (m.isEmpty()) continue;
						// 验证 value 是 AdvancementEntry
						Object firstVal = m.values().iterator().next();
						if (firstVal instanceof AdvancementEntry) {
							result = (java.util.Collection<AdvancementEntry>)(Object) m.values();
							diagPath = "field:" + f.getName();
							break outer;
						}
					} catch (Throwable t) {
						diagErr = "field-" + f.getName() + "→" + t.getClass().getSimpleName();
					}
				}
				klass = klass.getSuperclass();
			}
		}

		// 3) ADV_SEQUENCE hardcoded fallback
		if (result == null || result.isEmpty()) {
			java.util.List<AdvancementEntry> fallback = new java.util.ArrayList<>();
			for (String id : ADV_SEQUENCE) {
				try {
					AdvancementEntry e = server.getAdvancementLoader().get(Identifier.of(id));
					if (e != null) fallback.add(e);
				} catch (Throwable ignored) {}
			}
			result = fallback;
			diagPath = "hardcoded_fallback";
		}

		// 一次性 dump:loader 实际 class 名 + 走的路径 + 头 30 个 advancement id
		if (ENUMERATE_DUMPED.compareAndSet(false, true)) {
			java.util.List<String> sample = new java.util.ArrayList<>();
			int count = 0;
			for (AdvancementEntry e : result) {
				sample.add(e.id().toString());
				if (++count >= 30) break;
			}
			// V5.49: 改走 TaskLogger,受 debugVirtualTasks 开关控制
			com.maohi.fakeplayer.TaskLogger.logRaw("SYSTEM", "enumerate_loader_dump",
				"loaderClass", loaderClassName,
				"path", diagPath,
				"err", diagErr,
				"size", result.size(),
				"sample", sample);
		}

		return result;
	}

	/**
	 * 查询 personality 是否已解锁某档里程碑。
	 *
	 * P22 重构:从精确匹配改为 "精确 + endsWith 模糊" 二段匹配。
	 *   背景:syncFromVanilla 现在抄写的是 vanilla 真实 ID(如 "minecraft:story/mine_wood"),
	 *   而 phase 系统调用方传入的是逻辑 ID(如 "story/mine_wood"),两者字符串不等。
	 *   精确 contains 永远 miss → phase 跃迁判定永远 false → bot 卡 STONE_AGE。
	 *   endsWith 让 "minecraft:story/mine_wood".endsWith("story/mine_wood") = true,自然命中。
	 */
	public static boolean hasUnlocked(Personality personality, String advId) {
		if (personality == null || advId == null) return false;
		if (personality.unlockedAdvancements.contains(advId)) return true;
		// 模糊匹配:vanilla 真实 ID 通常带 "minecraft:" 前缀,endsWith 覆盖
		for (String stored : personality.unlockedAdvancements) {
			if (stored.endsWith(advId) || stored.endsWith(":" + advId)) return true;
		}
		return false;
	}

	/** 仅用于 phase 系统的钻石阶段判定（保留旧 API 兼容） */
	public static boolean canGrantDiamondAchievement(Personality personality) {
		if (personality == null) return false;
		return personality.growthPhase != null
			&& personality.growthPhase.ordinal() >= GrowthPhase.DIAMOND_AGE.ordinal();
	}
}
