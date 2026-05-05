package com.maohi.fakeplayer.tick;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * findNearestBlock 缓存(V5.20:从 VirtualPlayerManager 提取)
 *
 * 把 8x8x8 区块网格内的查找结果缓存 30 秒,叠加 MSPT 自适应半径(Lag Guard)。
 * 假人挖完一个方块时,通过 invalidate() 清掉对应位置缓存,避免回头再挖空气。
 *
 * 线程安全:由 ConcurrentHashMap 保证。
 */
public final class BlockScanCache {

	private static final long CACHE_TTL_MS = 30_000L;

	// key = "x>>3,y>>3,z>>3,type"; value = [BlockPos, expireTime]
	private final Map<String, Object[]> cache = new ConcurrentHashMap<>();

	/**
	 * 查找最近的方块。
	 * MSPT 自适应:
	 *   ≤35  → 半径 20(流畅)
	 *   ≤50  → 半径 12(轻卡)
	 *   >50  → 半径 8(卡顿)
	 */
	public BlockPos findNearestBlock(MinecraftServer server, ServerWorld world, BlockPos pos, int radius, String type) {
		String cacheKey = key(pos, type);
		Object[] cached = cache.get(cacheKey);
		if (cached != null && System.currentTimeMillis() < (long) cached[1]) return (BlockPos) cached[0];

		double mspt = server.getAverageTickTime();
		int maxRadius;
		if (mspt <= 35) maxRadius = 20;
		else if (mspt <= 50) maxRadius = 12;
		else maxRadius = 8;
		if (radius > maxRadius) radius = maxRadius;

		BlockPos result = null;
		int yMin = type.contains("ore") ? Math.max(-64, pos.getY() - 60) - pos.getY() : -2;
		int yMax = type.contains("ore") ? 2 : 2;
		for (int x = -radius; x <= radius && result == null; x++) {
			for (int y = yMin; y <= yMax && result == null; y++) {
				for (int z = -radius; z <= radius && result == null; z++) {
					BlockPos p = pos.add(x, y, z);
					if (net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(p).getBlock()).getPath().contains(type)) {
						result = p;
					}
				}
			}
		}
		cache.put(cacheKey, new Object[]{result, System.currentTimeMillis() + CACHE_TTL_MS});
		return result;
	}

	/**
	 * 失效指定位置 + 类型的缓存(假人挖完该方块后调用)
	 */
	public void invalidate(BlockPos pos, String type) {
		cache.remove(key(pos, type));
	}

	private static String key(BlockPos pos, String type) {
		return (pos.getX() >> 3) + "," + (pos.getY() >> 3) + "," + (pos.getZ() >> 3) + "," + type;
	}
}
