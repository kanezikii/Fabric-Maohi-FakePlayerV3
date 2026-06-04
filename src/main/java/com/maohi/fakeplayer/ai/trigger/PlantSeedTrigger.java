package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * A Seedy Place: 用锄头开耕地 + 在耕地上种麦种 (V5.22 第一阶段必修)
 *
 * 真人前 1 小时几乎必打——砍草掉麦种就会顺手种。但我们假人之前完全没种田行为。
 *
 * vanilla 触发链:
 *   1. HoeItem.useOnBlock(grass_block) → till_dirt criterion → grass→farmland
 *   2. WheatSeedsItem(BlockItem).useOnBlock(farmland) → placed_block criterion → husbandry/plant_seed
 *
 * 实现:一次 tick 内顺序发两个交互包(锄草→种种子),vanilla 同步处理。
 */
public final class PlantSeedTrigger implements AchievementTrigger {

	public static final PlantSeedTrigger INSTANCE = new PlantSeedTrigger();
	private static final String ADV_ID = "husbandry/plant_seed";

	private PlantSeedTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{20_000L, 90_000L}; } // 20s~90s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		return player.getEntityWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD;
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责

		PlayerInventory inv = player.getInventory();

		// 选种子:麦种最常见,甜菜种作兜底
		int seedSlot = TriggerUtil.findItemSlot(inv, Items.WHEAT_SEEDS);
		Item seedItem = Items.WHEAT_SEEDS;
		if (seedSlot == -1) {
			seedSlot = TriggerUtil.findItemSlot(inv, Items.BEETROOT_SEEDS);
			seedItem = Items.BEETROOT_SEEDS;
		}
		// V5.72: 没种子 → 破附近草丛(short_grass/tall_grass/fern)收麦种(vanilla ~12.5% 掉率),
		//   下个周期再走锄地+种植链。真人前期种田前也是先砍草攒种子;种子也同时供 BreedAnimals 喂鸡。
		if (seedSlot == -1) return harvestSeedsFromGrass(player, personality);

		// 必须有锄头才能开耕地
		int hoeSlot = findHoeSlot(inv);
		if (hoeSlot == -1) return false;

		// 找附近草方块——种子只能种在 farmland 上,grass→farmland 用锄头转
		BlockPos grass = findGrassBlock(player, 5);
		if (grass == null) return false;

		// 远了先派任务走过去
		if (player.squaredDistanceTo(Vec3d.ofCenter(grass)) > 16.0) {
			personality.taskTarget = grass;
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 600; // 30s = 600 ticks (V5.43.4 ms→tick)
			return false;
		}

		Vec3d grassTopCenter = Vec3d.ofCenter(grass.up()).subtract(0, 0.5, 0);
		BlockHitResult hit = new BlockHitResult(grassTopCenter, Direction.UP, grass, false);

		// Step 1: 切锄头,右键草地 → 转 farmland
		if (hoeSlot >= 9) {
			TriggerUtil.swapToHotbar(player, hoeSlot, 0);
			hoeSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, hoeSlot);
		TriggerUtil.facePoint(player, grassTopCenter);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);

		// Step 2: 切种子,右键 farmland → 种植成 wheat[age=0]
		// 重新查 seedSlot——swapToHotbar 上面可能已挪动
		seedSlot = TriggerUtil.findItemSlot(inv, seedItem);
		if (seedSlot == -1) return false;
		if (seedSlot >= 9) {
			TriggerUtil.swapToHotbar(player, seedSlot, 1);
			seedSlot = 1;
		}
		PacketHelper.setSelectedSlot(player, seedSlot);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);

		// V5.50: 走完完整锄草+种种子序列 → Registry 据此调 broadcastVanillaGrant 触发广播
		return true;
	}

	/** 找任意材质锄头(wooden/stone/iron/...) */
	private static int findHoeSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			String id = net.minecraft.registry.Registries.ITEM
				.getId(inv.getStack(i).getItem()).getPath();
			if (id.endsWith("_hoe")) return i;
		}
		return -1;
	}

	/**
	 * V5.72: 没麦种时破附近草丛收种子。短草 ~12.5% 掉麦种,高草更高。破完掉落物由 bot 现有拾取
	 *   (simulateEntityInteraction / vanilla 碰撞)收回,下个周期再走种植链。始终返 false
	 *   (本周期没完成"锄地+种植"动作链,不该广播)。
	 */
	private static boolean harvestSeedsFromGrass(ServerPlayerEntity player, Personality personality) {
		BlockPos grass = findGrassPlant(player, 4);
		if (grass == null) return false;
		// 远了先走过去(复用与种植链相同的 EXPLORING 派路语义)
		if (player.squaredDistanceTo(Vec3d.ofCenter(grass)) > 16.0) {
			personality.taskTarget = grass;
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 600; // 30s = 600 ticks
			return false;
		}
		TriggerUtil.facePoint(player, Vec3d.ofCenter(grass));
		player.swingHand(Hand.MAIN_HAND, true);
		// 直接服务端 breakBlock(dropLoot=true)— 与 StripMineBehavior.mineBlock 同款落地路径,
		//   走 vanilla 草丛 loot table 掉麦种。
		ServerWorld world = player.getEntityWorld();
		world.breakBlock(grass, true, player);
		return false;
	}

	/** 找附近草丛(short_grass/tall_grass/fern)— 破之掉麦种。只搜地表层 ±1,切比雪夫壳层由近及远。 */
	private static BlockPos findGrassPlant(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -d; dz <= d; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
						BlockPos p = center.add(dx, dy, dz);
						// V5.59+: chunk-ready 预检,跨 chunk 时跳过未就绪坐标(同 findGrassBlock)
						if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
								world, p.getX() >> 4, p.getZ() >> 4)) continue;
						String id = net.minecraft.registry.Registries.BLOCK.getId(
							world.getBlockState(p).getBlock()).getPath();
						if (id.equals("short_grass") || id.equals("tall_grass") || id.equals("fern")) {
							return p;
						}
					}
				}
			}
		}
		return null;
	}

	/** 在 player 周围 radius 格内找最近的 grass_block(只搜地表层 ±2) */
	private static BlockPos findGrassBlock(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dy = -2; dy <= 1; dy++) {
					for (int dz = -d; dz <= d; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
						BlockPos p = center.add(dx, dy, dz);
						// V5.59+: chunk-ready 预检，跨 chunk 时跳过未就绪坐标
						if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
								world, p.getX() >> 4, p.getZ() >> 4)) continue;
						if (!world.getBlockState(p).isOf(Blocks.GRASS_BLOCK)) continue;
						// 头顶必须是空气(锄头要求草地上方空)
						if (!world.getBlockState(p.up()).isAir()) continue;
						return p;
					}
				}
			}
		}
		return null;
	}
}
