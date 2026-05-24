package com.maohi.fakeplayer.ai;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能路径规避系统 (V3)
 *
 * V5.23 优化:
 *   1. 路径缓存:findPath 的入口/目标坐标按 8 格分桶为 cacheKey,5 秒 TTL,
 *      减轻多假人在同一区域(MOVE_TO_TARGET 早期常见)反复跑 A* 的压力。
 *   2. 邻居 cost 区分:原实现用平地距离启发,所有方向 cost = 1 → A* 偏好垂直跳跃路径
 *      (跳跃看起来步数少但实际有 1 tick 抬升+反作弊检测高度变化)。新版区分:
 *      平地 1.0、下台阶 1.2、跳跃上台阶 1.5、跨越 2 格 2.4。
 *   3. MAX_SEARCH_STEPS 由 32 提升到 64,大房间寻路更可靠;同时 32 节点的 visited 也保留
 *      回退路径(原行为)。
 *   4. BlockState 的小型 ThreadLocal LRU 缓存,避免一次 findPath 对同一 BlockPos 重复 getBlockState。
 *
 * V5.41 Y 轴邻居扩展(借鉴 Baritone):
 *   1. getNeighbors 加 4 个"楼梯上爬"邻居(NSEW.up(2), cost 2.5):解决 bot 卡在矿坑沿
 *      无法爬上 2 格高差的地形(Stone Age 末期下井必经路径)。
 *   2. heuristic 加入 0.5 权重 Y 轴分量:让 A* 感知目标在下方时优先向下探索,
 *      而非全局贪心地只跑 XZ 平面而绕远。
 *   3. pathCacheKey 加 Y 桶(8 格粒度):地面 bot 和地下 bot 同 XZ 位置不再共用同一条缓存路径。
 */
@SuppressWarnings("deprecation")
public class PathfindingNavigation {

	/** A* 搜索最大步数 */
	// V5.40 64 → 512:64 节点只够 ~8 格直线;森林环境绕 1~2 棵树就溢出 → path 返空 →
	// MovementController 5s 冷却内直线撞树叶 → expire。512 节点能覆盖 ~22 格半径任意拓扑,
	// worst-case 单次 ~1ms,5s 缓存摊销;100 bot 同时 worst-case 也只 ~100ms 不重叠。
	// P22 G: 512 → 2048。STONE_AGE bot 常需走 40~60 格找树/石(EXPLORE_RADIUS=30~36 配合
	//   force_explore cap=80),22 格半径覆盖不到 → A* 永远 empty → blocked_no_path 死循环。
	//   2048 节点覆盖 ~45 格半径,匹配 setExplore 实际距离。worst-case 单次 ~4ms,5s cache
	//   + wall-clock 错峰摊销实际峰值 << 100ms。
	// V5.43.5 P-3.I: 2048 → 4096。jungle biome 叶子密集 + 树多 + 起伏地形,2048 节点经常
	//   绕几棵树就溢出 → A* 返空 → blocked_no_path 死循环(本次 P22 log IronSky 10+ 次
	//   连续 task_fail blocked_no_path,force_explore escalation 累到 6 也救不回)。
	//   4096 节点覆盖 ~64 格半径,匹配 force_explore 最大 80 格半径。worst-case 单次 ~8ms,
	//   CPU 富裕 + 5s cache 错峰摊销实际峰值 < 200ms 可承受。
	private static final int MAX_SEARCH_STEPS = 4096;

	/** 路径缓存 TTL */
	private static final long PATH_CACHE_TTL_NS = 5_000_000_000L; // 5 秒
	/** 缓存条目上限,防止长期运行内存膨胀 */
	private static final int PATH_CACHE_MAX = 256;
	/** 起点/终点分桶粒度(8 格内视为同一目标) */
	private static final int PATH_CACHE_BUCKET = 8;

	private static final ConcurrentHashMap<Long, CacheEntry> PATH_CACHE = new ConcurrentHashMap<>();

	/**
	 * V5.59: 真正非阻塞的 chunk 访问 — 只返回已在 WorldChunk(FULL)状态的 chunk,未就绪即返 null。
	 *
	 * <p>背景:vanilla {@code ServerChunkManager.getChunk(x, z, FULL, false)} 在主线程调用且 4-slot
	 * 缓存未命中时会调 {@code mainThreadExecutor.runTasks(future::isDone)} 强制清空积压的 chunk gen
	 * 任务队列,主线程 park 数秒。watchdog 抓到一次 1.148s 卡顿就来自此条路径(bot spawn →
	 * {@code getSafeSpawnY}),极端场景下能卡 17 秒。
	 *
	 * <p>本方法绕开 slow path:通过 ServerChunkManagerAccessor mixin 直接拿 ChunkHolder,
	 * {@link ChunkHolder#getWorldChunk()} 是 O(1) 状态查询,不存在/未就绪即 null。<b>整条路径无
	 * task pump、无 park</b>。
	 *
	 * <p>失败兜底:Mixin 加载失败 / ChunkHolder API 改名等异常时 catch Throwable 返 null,调用方
	 * 应已经过 {@code world.isChunkLoaded(x, z)} 之类的预检,null 路径仅作为"chunk 未就绪"处理。
	 */
	private static WorldChunk getChunkIfReady(ServerWorld world, int chunkX, int chunkZ) {
		try {
			ServerChunkManager scm = world.getChunkManager();
			com.maohi.mixin.ServerChunkManagerAccessor accessor =
				(com.maohi.mixin.ServerChunkManagerAccessor) scm;
			ChunkHolder holder = accessor.maohi$getChunkHolder(ChunkPos.toLong(chunkX, chunkZ));
			if (holder == null) return null;
			return holder.getWorldChunk();
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * V5.59 public 版本:供项目内热路径(扫块循环、AI 决策)做主动 chunk 预检。
	 * 调用方应在 BlockPos / chunk 坐标循环外做一次,缓存结果,避免重复查询。
	 */
	public static boolean isChunkReady(ServerWorld world, int chunkX, int chunkZ) {
		return getChunkIfReady(world, chunkX, chunkZ) != null;
	}

	/**
	 * V5.59 安全 getBlockState 包装 — 非阻塞版本。
	 *
	 * <p>背景:vanilla {@code world.getBlockState(pos)} 内部调 {@code getChunk(FULL, false)},
	 * chunk 未加载时会 pump 主线程任务队列等待加载,实测 bot AI 扫块循环命中未加载 chunk →
	 * 卡 1~17s。watchdog 已抓到 CraftingBehavior.findBlockNearby 这种 169×7 次 getBlockState
	 * 循环里随便一个未加载 chunk 就把整个主线程冻住。
	 *
	 * <p>本方法先用 getChunkIfReady 做 O(1) 状态确认,只有 chunk 已 FULL 才调真实 getBlockState
	 * (此时绝不阻塞,vanilla 走 fast cache hit 路径);未加载即返 null。
	 *
	 * <p><b>调用方语义</b>:返 null = "未知,该位置 chunk 未加载,请跳过/重试"。<b>不</b>等同 AIR。
	 * 调用方应用 {@code if (state == null) continue;} 等模式,绝不要 .isOf() 调用前不查 null。
	 *
	 * <p><b>性能</b>:O(1) chunk 查询 + 1 次 getBlockState。比原直接调用多一次 hashmap lookup,
	 * 在 chunk 已加载的常见情形下额外开销 < 50ns,可忽略。
	 *
	 * @param world ServerWorld 实例
	 * @param pos 目标位置
	 * @return 已加载位置返 BlockState,未加载返 null
	 */
	@org.jetbrains.annotations.Nullable
	public static net.minecraft.block.BlockState safeGetBlockState(ServerWorld world, BlockPos pos) {
		WorldChunk chunk = getChunkIfReady(world, pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null) return null;
		return chunk.getBlockState(pos);
	}

	private static final class CacheEntry {
		final List<BlockPos> path;
		final long expireAtNs;
		CacheEntry(List<BlockPos> path, long expireAtNs) {
			this.path = path;
			this.expireAtNs = expireAtNs;
		}
	}

	/**
	 * 获取指定坐标的安全地面高度(对接 1.21.11 物理层)。
	 *
	 * 旧 3-arg 版本:chunk 未加载时回退到 `world.getBottomY()` (-64 / 0)。
	 * 仅在调用方有自己的范围守卫时才安全使用(如 VPM 高度守卫的 `> 0 && < 100` 过滤,
	 * 或 PhaseNether.findPortalBuildSpot 的"建造位置不合法就跳过"链路)。
	 *
	 * 任务派发(surfacePoint 系列)请改用 {@link #getSafeTopY(ServerWorld,int,int,int)},
	 * 传 player.getBlockY() 作为 fallback,避免 chunk 未加载时把假人引向 Y=-64 虚空。
	 */
	public static int getSafeTopY(ServerWorld world, int x, int z) {
		return getSafeTopY(world, x, z, world.getBottomY());
	}

	/**
	 * V5.24: 带 fallbackY 的高度查询。
	 * - chunk 未加载 → 返 fallbackY
	 * - heightmap 命中 ≤ bottomY(空气柱,无任何固体方块)→ 也视为无效 → 返 fallbackY
	 * - 否则返 MOTION_BLOCKING 顶面
	 */
	public static int getSafeTopY(ServerWorld world, int x, int z, int fallbackY) {
		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		// V5.59: 改走非阻塞 getChunkIfReady,避免 vanilla getChunk(FULL,false) 在主线程上 pump 任务队列
		WorldChunk chunk = getChunkIfReady(world, chunkX, chunkZ);
		if (chunk == null) return fallbackY;
		int localX = x & 15;
		int localZ = z & 15;
		int height = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING).get(localX, localZ);
		// 整列空气(罕见:洞穴+无地表生成,heightmap 返 bottomY)→ 视为 chunk 未加载
		if (height <= world.getBottomY()) return fallbackY;
		return height;
	}

	/**
	 * V5.40 spawn 专用:用 MOTION_BLOCKING_NO_LEAVES 跳过树叶,避免 bot 落在树冠层。
	 *   getSafeTopY 用 MOTION_BLOCKING(包含 leaves)→ 森林里 spawn 落在 leaves 顶 y=80+,
	 *   bot 被叶子包围 → 走不出 → 反复 task_fail → 永远 0 成就。
	 *   NO_LEAVES 把树叶当透明,heightmap 落到 leaves 下面的真实地表(草/土/石头)。
	 *   找不到时回退普通 getSafeTopY。
	 */
	public static int getSafeSpawnY(ServerWorld world, int x, int z, int fallbackY) {
		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		// V5.59: 改走非阻塞 getChunkIfReady。watchdog 抓到的 bot spawn 卡 1.148s 就来自此条路径,
		//   旧实现 getChunk(FULL,false) 在 4-slot 缓存 miss 时 pump 整个 task 队列,极端能 17 秒。
		WorldChunk chunk = getChunkIfReady(world, chunkX, chunkZ);
		if (chunk == null) return fallbackY;
		int localX = x & 15;
		int localZ = z & 15;
		int height = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES).get(localX, localZ);
		if (height <= world.getBottomY()) return getSafeTopY(world, x, z, fallbackY);
		return height;
	}

	/**
	 * 判定前方是否为危险区域(如熔岩或高处坠落风险)
	 */
	public static boolean isDangerAhead(ServerWorld world, BlockPos pos) {
		// planA B-2a: chunk 未加载 → 视为危险。
		//   旧实现:未加载 chunk 的方块 getBlockState 返回 air → 落差/岩浆/火检测全失效 →
		//     bot 在 chunk-loading race 中走进未加载区 → vanilla 物理触发 chunk gen 后地形不是
		//     之前判定的"air" → bot 自由落体进 cave → 卡 y=30~44 → 10 天 0 成就。
		//   现行:未加载视为危险,bot 在 chunk 边界停下,等下一 tick 加载完再走。
		//   开销:chunk lookup O(1) hashmap 查,不主动加载(ChunkStatus.FULL, false 第 4 参 false)。
		if (!isChunkFullyLoaded(world, pos)) {
			return true;
		}
		// 1. 检测是否会跌落超过 3 格 — 必须 y-1/y-2/y-3 三格都 air 才算 danger
		// V5.43.5 P-3.E bug 修复: 原 `below.isAir() && below.down(2).isAir()` 跳过了 y-2,
		//   检测的是 y-1 + y-3 两点而非 y-1/y-2/y-3 连续。
		//   误判情形(被本 bug 害)— y-1 air, y-2 solid, y-3 air:y-2 是一块凸出于深洞顶端的孤立方块,
		//     bot 只下 1 格就站住,完全安全;但旧条件把它判 danger → bot 不肯走出口。
		//   反例自查 — y-1 air, y-2 air, y-3 solid(2 格落差):below.isAir() ✓ 但 below.down(2)
		//     = solid → 旧条件 false ✓ 走通,新条件依旧 false ✓ 走通,行为不变。
		//   反例自查 — y-1/y-2/y-3 全 air(≥3 格悬崖):新旧条件都 true,行为不变。
		BlockPos below = pos.down();
		if (world.getBlockState(below).isAir()
				&& world.getBlockState(below.down()).isAir()
				&& world.getBlockState(below.down(2)).isAir()) {
			return true;
		}
		// 2. 检测脚下是否是危险流体(岩浆等)或危险方块(岩浆块、火)
		net.minecraft.block.BlockState state = world.getBlockState(pos);
		if (state.getFluidState().getFluid().matchesType(net.minecraft.fluid.Fluids.LAVA)
			|| state.isOf(net.minecraft.block.Blocks.FIRE)
			|| world.getBlockState(pos.down()).isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)) {
			return true;
		}
		// V5.43.3 P-3.D: 删除原 V5.25 P4-1 的"深水=danger"判断。
		//   原假设:bot 没法游泳逃生会淹死。
		//   实际:doSmartMove 已 wantJump=true if isTouchingWater (line 134-136),vanilla
		//     swim-up impulse 持续把 bot 浮到水面,任何深度水都不会淹死。
		//   旧行为副作用:spawn 在水岛/沙滩/河边的 bot,4 邻全是 2+ 格深水 → 永远 stopMovement
		//     → 21 分钟 0 移动 → setExplore/force_explore 200 块路一步走不到 → 0 树 0 成就。
		//   日志证据(V5.43.2 5af03a5 第二次跑测): 9 bot 21 分钟全 logs=0,bot chat
		//     "swimming back"/"ooh cliff" 透露 spawn 在水/悬崖边。
		//   保留:lava / 火 / magma_block / 跌落≥3 格 — 这些都不是 swim-up 能救的真 danger。
		return false;
	}

	/**
	 * P25: 从 pos 向下扫连续 air 格数,等价于 bot 站到 pos 后的 fall distance。
	 *   vanilla 跌落伤害公式:damage = max(0, fall - 3)。所以:
	 *     fall ≤ 3 = 无伤,fall = 4 = 1 心,fall = 5 = 2 心,...
	 *   maxScan 限制扫描深度,超过返 maxScan+1(视为深悬崖)。
	 *
	 *   设计:不替代 isDangerAhead(A* 仍用保守阈值),而是给 doSmartMove 一个细粒度
	 *   API 做 HP-guarded 决策——bot 健康时可主动跳 4 格扣 1 心断崖以脱困,低 HP 时不冒险。
	 */
	public static int getFallDepth(ServerWorld world, BlockPos pos, int maxScan) {
		BlockPos cur = pos.down();
		int depth = 0;
		while (depth < maxScan && world.getBlockState(cur).isAir()) {
			cur = cur.down();
			depth++;
		}
		// 已扫 maxScan 格仍 air → 视为深悬崖,返回 maxScan+1 让调用方判 danger
		return world.getBlockState(cur).isAir() ? maxScan + 1 : depth;
	}

	/**
	 * P25: 从 isDangerAhead 抽出的"非落差类危险方块"检查 —— 岩浆 / 火 / 岩浆块 / chunk 未加载。
	 *   doSmartMove 用 getFallDepth + HP 自行做落差决策,但岩浆/火等仍然必拒,所以单独这个 API。
	 */
	public static boolean isHazardousBlock(ServerWorld world, BlockPos pos) {
		if (!isChunkFullyLoaded(world, pos)) return true;
		net.minecraft.block.BlockState state = world.getBlockState(pos);
		if (state.getFluidState().getFluid().matchesType(net.minecraft.fluid.Fluids.LAVA)
			|| state.isOf(net.minecraft.block.Blocks.FIRE)
			|| world.getBlockState(pos.down()).isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)) {
			return true;
		}
		return false;
	}

	/**
	 * planA B-2:检查 chunk 是否已加载到 FULL 状态(可读真实方块数据)。
	 *   不主动加载(force=false):若返 null 说明 chunk 未到 FULL,调用方应视为"未知地形"
	 *   而非"已确认空气/空地"。这是修复"未加载 chunk 误判为安全"的关键。
	 */
	public static boolean isChunkFullyLoaded(ServerWorld world, BlockPos pos) {
		int chunkX = pos.getX() >> 4;
		int chunkZ = pos.getZ() >> 4;
		// V5.59: 改走非阻塞 getChunkIfReady。本方法在 A* / doSmartMove 热路径每次邻居枚举都调,
		//   旧实现潜在的 task pump 会让 100 bot 同时寻路场景下产生主线程级联 park。
		return getChunkIfReady(world, chunkX, chunkZ) != null;
	}

	/**
	 * 判定某个坐标是否可以行走(地面存在且上方 2 格无遮挡)
	 *
	 * V5.24 P1: 加入 1 格深水的可达判定 — 真人能蹚 1 格深水(脚浸水但头出水面),
	 * 旧实现把任何 isLiquid() 都视为障碍,导致 A* 永远跨不过小溪。
	 * 仅放行 water,lava 仍然不可达(由 isDangerAhead 兜底)。
	 *
	 * 1 格水可达条件: 脚下=固体, 当前格=water, 头顶=air
	 *  (2 格深水会有 ground=water 命中,自然被排除,bot 不会被引去淹死)
	 */
	public static boolean isWalkable(ServerWorld world, BlockPos pos) {
		net.minecraft.block.BlockState groundState = world.getBlockState(pos.down());
		net.minecraft.block.BlockState atState = world.getBlockState(pos);
		net.minecraft.block.BlockState upState = world.getBlockState(pos.up());

		// V5.25 P4-2: ladder column passthrough - vanilla LivingEntity.travel isClimbing() path
		//   converts forward speed to vertical climb when in a ladder. Allow upState=ladder too,
		//   so A* can route through stacked ladder blocks (whole column treated as walkable).
		if (atState.getBlock() instanceof net.minecraft.block.LadderBlock) return true;

		// 头顶必须是空气(玩家身高约 1.8 格)
		if (!upState.isAir()) return false;

		// V5.24: 1 格水检查 — 脚下固体 + 当前格 water + 头顶 air
		net.minecraft.fluid.Fluid atFluid = atState.getFluidState().getFluid();
		boolean atIsWater = atFluid == net.minecraft.fluid.Fluids.WATER
			|| atFluid == net.minecraft.fluid.Fluids.FLOWING_WATER;
		if (atIsWater && !groundState.isAir() && !groundState.isLiquid()) return true;

		// 标准平地: 脚下固体 + 当前格 air
		if (groundState.isAir() || groundState.isLiquid()) return false;
		if (!atState.isAir()) return false;
		return true;
	}

	/**
	 * A* 寻路:计算从起点到目标点的可行走路径
	 * 轻量实现:只在 XZ 平面搜索(保持当前 Y),限制搜索步数。
	 *
	 * V5.23: 加入路径缓存与邻居 cost 区分。
	 */
	public static List<BlockPos> findPath(ServerWorld world, BlockPos start, BlockPos goal) {
		if (start.getX() == goal.getX() && start.getZ() == goal.getZ()) {
			return Collections.emptyList();
		}

		// V5.23: 缓存命中
		long cacheKey = pathCacheKey(start, goal);
		long nowNs = System.nanoTime();
		CacheEntry cached = PATH_CACHE.get(cacheKey);
		if (cached != null) {
			if (cached.expireAtNs > nowNs) {
				return cached.path;
			}
			PATH_CACHE.remove(cacheKey);
		}
		// LRU 兜底:超容量时清空(简单粗暴胜过频繁淘汰算法,寻路缓存命中收益本身大)
		if (PATH_CACHE.size() > PATH_CACHE_MAX) PATH_CACHE.clear();

		PriorityQueue<AStarNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		Map<Long, AStarNode> visited = new HashMap<>();

		AStarNode startNode = new AStarNode(start, 0, heuristic(start, goal), null);
		openSet.add(startNode);
		// P22 修复:visited key 改 BlockPos.asLong() (vanilla 26X+12Y+26Z bit-packed long)。
		//   原 blockPosKey 只打 X/Z,getNeighbors 含 up/down/up(2) 时同一 XZ 列不同 Y 节点折叠
		//   成同一 visited entry → 任意一个被访问后,该列其他高度全被剪 → cave/树冠/楼梯跨 Y
		//   寻路被错误剪枝(jungle blocked_no_path 死循环的根因)。PATH_CACHE key 已在 V5.41
		//   加 Y 桶,visited 漏改是半截修复。
		visited.put(start.asLong(), startNode);

		int steps = 0;
		while (!openSet.isEmpty() && steps < MAX_SEARCH_STEPS) {
			AStarNode current = openSet.poll();
			steps++;

			double distToGoal = Math.abs(current.pos.getX() - goal.getX())
				+ Math.abs(current.pos.getZ() - goal.getZ());
			// V5.40 1.5 → 3.5:与 mining 的 dist <= 16(VPM:1346)对齐。原阈值要求精确命中,
			// target 周围 1 格被树叶/不可达方块包围时整条路径直接返空,bot 5s 冷却撞墙。
			// 3.5 让 A* 终点贴近"挖矿可达半径",caller 自己走完最后 2~3 格。
			if (distToGoal <= 3.5) {
				List<BlockPos> path = reconstructPath(current);
				PATH_CACHE.put(cacheKey, new CacheEntry(path, nowNs + PATH_CACHE_TTL_NS));
				return path;
			}

			// V5.23: 邻居附带 cost — 优先平地,跳跃/跨越走 cost 阶梯
			for (Neighbor nb : getNeighbors(current.pos)) {
				// P22 修复:同 startNode put,visited key 改 asLong() 保留 Y 维度,避免不同高度折叠
				long key = nb.pos.asLong();
				double tentativeG = current.g + nb.cost;

				AStarNode existing = visited.get(key);
				if (existing != null && tentativeG >= existing.g) continue;

				if (!isWalkable(world, nb.pos)) continue;
				if (isDangerAhead(world, nb.pos)) continue;

				// V5.41: up(2) 物理可达性校验。
				// 当目标比当前节点高 2 格时(楼梯上爬邻居),vanilla 最大跳跃高度为 ~1.25 格,
				// bot 必须能先接近崖壁底部才能触发跳跃。
				// 若 foot-level 水平过渡格(与 nb 同 XZ、与 current 同 Y)是实体方块,
				// 说明这是一面 2 格高的垂直崖壁:bot 无法穿透底部方块去接近崖壁→ 跳跃永远无法发生。
				// doSmartMove 会识别到 isBlocked+canJump=false → path.clear() → 下 tick 重取同一条缓存路径
				// → 每 tick 清路径空转循环,浪费 CPU 且 bot 原地卡死。
				// 修复:在 A* 阶段直接拒绝此节点,让搜索绕道或放弃。
				// 注:悬空平台(foot-level 格为空气)不会被拒绝——canJump 在那种地形也是 false 但
				//   bot 实际上会走平路绕过去,不会形成循环。
				// V5.46:本检查同时覆盖对角 up(2) — destination XZ 是对角格,footTransit 在
				//   该对角格的脚位,语义一致 (内角处堵死同样拦截)。
				if (nb.pos.getY() - current.pos.getY() == 2) {
					// foot-level 水平过渡格:与 nb 同 XZ,与 current 同 Y
					BlockPos footTransit = new BlockPos(nb.pos.getX(), current.pos.getY(), nb.pos.getZ());
					net.minecraft.block.BlockState footState = world.getBlockState(footTransit);
					// 固体碰撞体积存在 → 崖壁底部被封死 → 拒绝此 up(2) 邻居
					if (!footState.getCollisionShape(world, footTransit).isEmpty()) continue;
				}

				// V5.46 Baritone-style 对角 up(N) 内角卡身检查:
				//   diagonal up(1)/up(2) 邻居需额外校验对角两侧 cardinal 中间格至少一侧通透,
				//   避免 0.6 宽 hitbox 在双侧封死的内凹墙角对角通行时撞墙原地不动。
				if (!isDiagonalUpReachable(world, current.pos, nb.pos)) continue;

				double newF = tentativeG + heuristic(nb.pos, goal);
				AStarNode neighborNode = new AStarNode(nb.pos, tentativeG, newF, current);

				visited.put(key, neighborNode);
				openSet.add(neighborNode);
			}

		}

		// 搜索超时:返回朝目标方向最近的已访问点(近似路径)
		if (!visited.isEmpty()) {
			AStarNode closest = visited.values().stream()
				.min(Comparator.comparingDouble(n -> heuristic(n.pos, goal)))
				.orElse(null);
			if (closest != null && closest.parent != null) {
				List<BlockPos> path = reconstructPath(closest);
				PATH_CACHE.put(cacheKey, new CacheEntry(path, nowNs + PATH_CACHE_TTL_NS));
				return path;
			}
		}

		// 失败也缓存空结果,避免短时间反复跑 A* 撞同一堵墙(MovementController/VPM 的 pathfindCooldownUntil
		// 已经做了一层冷却,这里 5s 缓存等价于把那层 cooldown 落到 PathfindingNavigation 内)
		PATH_CACHE.put(cacheKey, new CacheEntry(Collections.emptyList(), nowNs + PATH_CACHE_TTL_NS));
		return Collections.emptyList();
	}

	/** 邻居元组:位置 + 进入它需要的代价 */
	private static final class Neighbor {
		final BlockPos pos;
		final double cost;
		Neighbor(BlockPos pos, double cost) { this.pos = pos; this.cost = cost; }
	}

	/**
	 * 邻居探测:平地 + 跳跃上台阶 + 下台阶 + 跨越 2 格(跳过坑) + 2 格落差。
	 * V5.23: 各方向 cost 不再统一为 1,贴合真实玩家:
	 *   平地 1.0 < 下台阶 1.2 < 跳跃 1.5 < 跨越 2 格 2.4
	 * V5.24 P1: 加 4 个 down(2) 邻居 — 真人能 2 格无伤坠落。
	 *   isDangerAhead 阈值是 ≥3 格落,所以 down(2) 不会被它否决,自然走 cost 阶梯。
	 * V5.41: 加 Y 轴邻居组(借鉴 Baritone):
	 *   - 楼梯上爬(NSEW.up(2), cost 2.5):跨越 2 格高差的台阶/矿坑边沿,
	 *     bot 拿到木镐后能爬出矿井并继续推进到 Stone Age。
	 *   注:原有 NSEW.down(1) cost 1.2 已在 V5.23 存在,未重复添加。
	 *   cost 2.5 > 跳跃 1.5:楼梯上爬代价更高,优先绕路平地;目标在上方时
	 *   heuristic Y 分量会驱动 A* 选这条路而非绕远。
	 */
	private static Neighbor[] getNeighbors(BlockPos pos) {
		return new Neighbor[] {
			// 平地 4 向
			new Neighbor(pos.north(), 1.0),
			new Neighbor(pos.south(), 1.0),
			new Neighbor(pos.east(), 1.0),
			new Neighbor(pos.west(), 1.0),
			// 跳跃上台阶 4 向(略高 cost)
			new Neighbor(pos.north().up(), 1.5),
			new Neighbor(pos.south().up(), 1.5),
			new Neighbor(pos.east().up(), 1.5),
			new Neighbor(pos.west().up(), 1.5),
			// 下台阶 4 向(中等 cost)
			new Neighbor(pos.north().down(), 1.2),
			new Neighbor(pos.south().down(), 1.2),
			new Neighbor(pos.east().down(), 1.2),
			new Neighbor(pos.west().down(), 1.2),
			// V5.0 A: 跨越探测(2 格远,模拟跳过 1 格坑) — 高 cost
			new Neighbor(pos.north(2), 2.4),
			new Neighbor(pos.south(2), 2.4),
			new Neighbor(pos.east(2), 2.4),
			new Neighbor(pos.west(2), 2.4),
			// V5.24 P1: 2 格落差(可控坠落不致伤),稍高于 down(1) 的 1.2
			new Neighbor(pos.north().down(2), 1.4),
			new Neighbor(pos.south().down(2), 1.4),
			new Neighbor(pos.east().down(2), 1.4),
			new Neighbor(pos.west().down(2), 1.4),
			// P25: 3 格落差,vanilla 公式 max(0,3-3)=0 无伤上限,允许 A* 算下坡路径过 3 格断崖。
			//   cost 1.8 > down(2) 1.4:略高让 A* 仍优先小台阶,只在 2 格台阶绕不开时选 3 格跳。
			//   4 格落差(扣 1 心)不加 A* 邻居 —— A* 没法读 HP,4 格冒险交给 doSmartMove 临时决策。
			new Neighbor(pos.north().down(3), 1.8),
			new Neighbor(pos.south().down(3), 1.8),
			new Neighbor(pos.east().down(3), 1.8),
			new Neighbor(pos.west().down(3), 1.8),
			// V5.41: 楼梯上爬(水平+2 格高,Baritone 借鉴) — 解决矿坑沿 2 格高差卡路
			// cost 2.5 > 跳跃 1.5:优先绕路平地;目标在上方时 heuristic Y 分量会驱动选此路
			new Neighbor(pos.north().up(2), 2.5),
			new Neighbor(pos.south().up(2), 2.5),
			new Neighbor(pos.east().up(2), 2.5),
			new Neighbor(pos.west().up(2), 2.5),
			// V5.46 Baritone-style 对角上一阶: 解决 5-Y 悬崖陷阱(bot 在山脚扫到山顶树 dy>4
			//   永远走不到的死循环)。让 A* 能拼出"沿坡螺旋爬阶"的路径,而非只能直上直下。
			//   cost 2.0 介于平地对角等效(走 N→E 两步 cost 2.0) 与 直上 up(1)+平 1 步 (1.5+1=2.5) 之间;
			//   对角 up(1) 兼顾水平进度 + 1 格爬升,适合自然山势的台阶状斜坡。
			//   物理可达性:由 isDiagonalUpReachable 在 A* 主循环内查 cardinal 两侧
			//   至少一侧通透(避免 cliff 内凹墙角 0.6 hitbox 卡身)。
			new Neighbor(pos.north().east().up(), 2.0),
			new Neighbor(pos.north().west().up(), 2.0),
			new Neighbor(pos.south().east().up(), 2.0),
			new Neighbor(pos.south().west().up(), 2.0),
			// V5.46 对角上 2 阶:跨越 2 格高差的对角台阶(常见于自然生成山势的台阶式岩面)。
			//   cost 3.5 > 直上 up(2) 2.5 + 平 1 cost = 3.5,平局让 A* 优先直上;只在
			//   直上 footTransit 被堵 / 对角能绕开时才被选中。
			//   doSmartMove 执行:面向对角 waypoint,vanilla 物理对 2 格高差走 jump→上一阶→再 jump
			//   的多 tick 链路自然成立(同直上 up(2))。
			new Neighbor(pos.north().east().up(2), 3.5),
			new Neighbor(pos.north().west().up(2), 3.5),
			new Neighbor(pos.south().east().up(2), 3.5),
			new Neighbor(pos.south().west().up(2), 3.5)
		};
	}

	/**
	 * V5.46 Baritone-style 对角 up(N) 邻居物理可达性检查:
	 *   bot bounding box 0.6×1.8×0.6,对角通行时身体扫过 2 个 cardinal 中间格 (N/S 投影 与 E/W 投影)。
	 *   若 from→to 在 from.Y 到 to.Y 任一层 Y 都「两侧 cardinal 中间格都被实体方块封死」,
	 *   则 bot 必撞内凹墙角无法对角穿过,直接拒绝该邻居。
	 *
	 *   非对角 up 邻居 (cardinal / 平地 / 下台阶) 不查 — 返 true 跳过。
	 *
	 * @param from 当前节点 (含 Y)
	 * @param to   候选邻居 (含 Y)
	 * @return true 表示对角通行可达 (或不是对角 up 邻居,跳过)
	 */
	private static boolean isDiagonalUpReachable(ServerWorld world, BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dz = to.getZ() - from.getZ();
		int dy = to.getY() - from.getY();
		// 仅校验对角 up(N):xz 各 ±1 且 y >= 1。其它形态返 true 直接通过
		if (Math.abs(dx) != 1 || Math.abs(dz) != 1) return true;
		if (dy < 1) return true;

		// from 处出发的两个 cardinal 中间格 (沿 x 与沿 z 方向投影)
		BlockPos midX = new BlockPos(from.getX() + dx, from.getY(), from.getZ());
		BlockPos midZ = new BlockPos(from.getX(), from.getY(), from.getZ() + dz);

		// 在 from.Y 到 to.Y 每一层 Y 查:两侧 cardinal 中间格至少一侧通透 (collision empty)
		for (int yOff = 0; yOff <= dy; yOff++) {
			BlockPos checkX = midX.up(yOff);
			BlockPos checkZ = midZ.up(yOff);
			net.minecraft.block.BlockState sX = world.getBlockState(checkX);
			net.minecraft.block.BlockState sZ = world.getBlockState(checkZ);
			boolean blockedX = !sX.getCollisionShape(world, checkX).isEmpty();
			boolean blockedZ = !sZ.getCollisionShape(world, checkZ).isEmpty();
			if (blockedX && blockedZ) return false; // 两侧都封死 → 对角通行受阻
		}
		return true;
	}

	/**
	 * 启发函数:XZ 曼哈顿距离 + 0.5 权重 Y 轴分量。
	 * V5.41: 引入 Y 轴感知,当目标在下方(矿井/地下)或上方时,
	 * A* 能在 XZ 路程相同时优先选择高度更接近目标的节点,
	 * 避免 bot 在地表绕远后才开始下井。
	 * 权重 0.5(< 1.0)保持可采纳性(admissible):不高估实际代价。
	 */
	private static double heuristic(BlockPos a, BlockPos b) {
		double xzDist = Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
		double yDist  = Math.abs(a.getY() - b.getY()) * 0.5;
		return xzDist + yDist;
	}

	/** BlockPos → long key(M7 fix: 偏移 30000000 避免负数符号扩展碰撞) */
	private static long blockPosKey(BlockPos pos) {
		return ((long)(pos.getX() + 30000000) << 32) | ((long)(pos.getZ() + 30000000) & 0xFFFFFFFFL);
	}

	/**
	 * V5.23: 路径缓存 key — start/goal 各自按 PATH_CACHE_BUCKET 分桶,
	 * 起点 8 格内、目标 8 格内视为同路径。
	 * V5.41: 加入 Y 桶(8 格粒度)。
	 * 背景:原实现仅用 XZ 4×16bit 组成 64bit key。加入 Y 轴邻居后,地下 bot(Y≈50)
	 * 与地面 bot(Y≈70)在同一 XZ 格时可能命中对方缓存的地表路径,
	 * 导致地下 bot 拿到一条完全不可达的路并反复失败。
	 * 修复:用 hash 把 Y 桶混入 key。由于 64bit 已满(sx16+sz16+gx16+gz16),
	 * 通过 XOR 方式把 Y 桶混入高低位,不改变 key 宽度但区分上下层。
	 */
	private static long pathCacheKey(BlockPos start, BlockPos goal) {
		int sx = (start.getX() / PATH_CACHE_BUCKET) & 0xFFFF;
		int sz = (start.getZ() / PATH_CACHE_BUCKET) & 0xFFFF;
		int gx = (goal.getX() / PATH_CACHE_BUCKET) & 0xFFFF;
		int gz = (goal.getZ() / PATH_CACHE_BUCKET) & 0xFFFF;
		// NOTE: Y 桶混入:起点/终点 Y 各用 8 格分桶后 XOR 到高/低 16bit,
		//       保证地下层(Y~50)与地面层(Y~70)生成不同 key,防止跨层缓存污染。
		int sy = ((start.getY() / PATH_CACHE_BUCKET) & 0xFF);
		int gy = ((goal.getY()  / PATH_CACHE_BUCKET) & 0xFF);
		long raw = ((long) sx << 48) | ((long) sz << 32) | ((long) gx << 16) | (long) gz;
		return raw ^ ((long) sy << 56) ^ ((long) gy << 8);
	}

	/** 从目标节点回溯路径 */
	private static List<BlockPos> reconstructPath(AStarNode node) {
		LinkedList<BlockPos> path = new LinkedList<>();
		AStarNode current = node;
		while (current != null && current.parent != null) {
			path.addFirst(current.pos);
			current = current.parent;
		}
		return path;
	}

	/** A* 节点 */
	private static class AStarNode {
		final BlockPos pos;
		final double g; // 起点到此点的实际代价
		final double f; // g + heuristic
		final AStarNode parent;

		AStarNode(BlockPos pos, double g, double f, AStarNode parent) {
			this.pos = pos;
			this.g = g;
			this.f = f;
			this.parent = parent;
		}
	}
}
