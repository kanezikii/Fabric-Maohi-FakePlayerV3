package com.maohi.fakeplayer.ai;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;

/**
 * 智能路径规避系统 (V3.2 A* 寻路)
 * 负责计算避障路径，防止假人机械性撞墙或掉入深坑。
 * 
 * V3.2 重写：
 * - 基础 A* 寻路：替代随机目标，让假人能绕过障碍
 * - 轻量级实现：限制搜索深度（32 步），避免卡主线程
 * - 兼容原接口：getSafeTopY / isDangerAhead 保留
 * 
 * V3.5: 屏蔽 Heightmap.Type 枚举的 @Deprecated 警告
 * Minecraft 1.21.11 过渡期正确使用 Chunk-based API，枚举警告不可避免但无害
 */
@SuppressWarnings("deprecation")
public class PathfindingNavigation {

	/** A* 搜索最大步数（防止卡顿，32 步 ≈ 32 格 ≈ 半个区块） */
	private static final int MAX_SEARCH_STEPS = 32;

	/**
	 * 获取指定坐标的安全地面高度 (对接 1.21.11 物理层)
	 */
	public static int getSafeTopY(ServerWorld world, int x, int z) {
		// 1.21.11 适配：使用 Chunk-based Heightmap API（旧版 getTopY 已废弃）
		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		Chunk chunk = (Chunk) world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (chunk != null) {
			int localX = x & 15;
			int localZ = z & 15;
			return chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING).get(localX, localZ);
		}
		// 回退：使用世界最低 Y 坐标
		return world.getBottomY();
	}

	/**
	 * 判定前方是否为危险区域（如熔岩或高处坠落风险）
	 */
	public static boolean isDangerAhead(ServerWorld world, BlockPos pos) {
		// 1. 检测是否会跌落超过 3 格
		BlockPos below = pos.down();
		if (world.getBlockState(below).isAir() && world.getBlockState(below.down(2)).isAir()) {
			return true;
		}
		// 2. 检测脚下是否是危险流体 (岩浆等)
		if (world.getBlockState(pos).getFluidState().isStill() 
			&& world.getBlockState(pos).getFluidState().getFluid().matchesType(net.minecraft.fluid.Fluids.LAVA)) {
			return true;
		}
		return false;
	}

	/**
	 * 判定某个坐标是否可以行走（地面存在且上方 2 格无遮挡）
	 */
	public static boolean isWalkable(ServerWorld world, BlockPos pos) {
		BlockPos ground = pos.down();
		// 脚下必须是实体方块
		if (world.getBlockState(ground).isAir() || world.getBlockState(ground).isLiquid()) return false;
		// 上方 2 格必须是空气（玩家身高约 1.8 格）
		if (!world.getBlockState(pos).isAir()) return false;
		if (!world.getBlockState(pos.up()).isAir()) return false;
		return true;
	}

	/**
	 * A* 寻路：计算从起点到目标点的可行走路径
	 * 轻量实现：只在 XZ 平面搜索（保持当前 Y），限制搜索步数
	 * 
	 * @param world 服务端世界
	 * @param start 起点（假人当前位置）
	 * @param goal 目标点
	 * @return 路径节点列表（不含起点，含目标），如果找不到返回空列表
	 */
	public static List<BlockPos> findPath(ServerWorld world, BlockPos start, BlockPos goal) {
		// 起点就是终点
		if (start.getX() == goal.getX() && start.getZ() == goal.getZ()) {
			return Collections.emptyList();
		}

		// A* 核心数据结构
		PriorityQueue<AStarNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		Map<Long, AStarNode> visited = new HashMap<>();

		AStarNode startNode = new AStarNode(start, 0, heuristic(start, goal), null);
		openSet.add(startNode);
		visited.put(blockPosKey(start), startNode);

		int steps = 0;
		while (!openSet.isEmpty() && steps < MAX_SEARCH_STEPS) {
			AStarNode current = openSet.poll();
			steps++;

			// 到达目标（允许 1 格误差）
			double distToGoal = Math.abs(current.pos.getX() - goal.getX()) + Math.abs(current.pos.getZ() - goal.getZ());
			if (distToGoal <= 1.5) {
				return reconstructPath(current);
			}

			// 扩展 4 个方向（上下左右，不含对角线）
			for (BlockPos neighbor : getNeighbors(current.pos)) {
				long key = blockPosKey(neighbor);

				// 已访问且更优则跳过
				if (visited.containsKey(key)) {
					AStarNode existing = visited.get(key);
					if (current.g + 1 >= existing.g) continue;
				}

				// 检查可通行性
				if (!isWalkable(world, neighbor)) continue;
				if (isDangerAhead(world, neighbor)) continue;

				double newG = current.g + 1;
				double newF = newG + heuristic(neighbor, goal);
				AStarNode neighborNode = new AStarNode(neighbor, newG, newF, current);

				visited.put(key, neighborNode);
				openSet.add(neighborNode);
			}
		}

		// 搜索超时：返回朝目标方向最近的已访问点（近似路径）
		if (!visited.isEmpty()) {
			AStarNode closest = visited.values().stream()
				.min(Comparator.comparingDouble(n -> heuristic(n.pos, goal)))
				.orElse(null);
			if (closest != null && closest.parent != null) {
				return reconstructPath(closest);
			}
		}

		return Collections.emptyList();
	}

	/** 5 方向邻居：4 个水平方向 + 上一格（跳跃）+ 下一格（下台阶） */
	private static BlockPos[] getNeighbors(BlockPos pos) {
		return new BlockPos[] {
			pos.north(), pos.south(), pos.east(), pos.west(),
			pos.north().up(), pos.south().up(), pos.east().up(), pos.west().up(),
			pos.north().down(), pos.south().down(), pos.east().down(), pos.west().down()
		};
	}

	/** 曼哈顿距离启发函数 */
	private static double heuristic(BlockPos a, BlockPos b) {
		return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
	}

	/** BlockPos → long key（M7 fix: 偏移 30000000 避免负数符号扩展碰撞） */
	private static long blockPosKey(BlockPos pos) {
		return ((long)(pos.getX() + 30000000) << 32) | ((long)(pos.getZ() + 30000000) & 0xFFFFFFFFL);
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
