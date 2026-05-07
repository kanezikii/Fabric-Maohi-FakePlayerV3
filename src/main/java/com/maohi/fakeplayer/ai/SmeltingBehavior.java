package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 冶炼行为(raw_iron → iron_ingot)
 * 从原 SurvivalMechanics 拆分(V5.20)
 *
 * V5.28.2 A.4 完整迁移:
 *   旧: 假装"野外便携小窑",纯计时器,到时 inv.setStack 转换原料 → 产物(凭空,无熔炉痕迹)
 *   新: 真双阶段熔炉协议
 *     阶段 1 (autoSmeltOres):
 *       - 找熔炉 + 检查原料/燃料
 *       - interactBlock 开 FurnaceScreenHandler
 *       - moveOneToHandlerSlot 把 1 raw_iron → input slot 0
 *       - moveOneToHandlerSlot 把 1 fuel → fuel slot 1
 *       - closeScreen
 *       - 记 smeltingFurnacePos + 设 smeltingTicks(略短于 vanilla 200 tick 烧炼周期)
 *     阶段 2 (tickSmelting 在 ticks==0 时):
 *       - 校验熔炉仍在 + 距离 < 5 格
 *       - interactBlock 重开 FurnaceScreenHandler
 *       - quickMove 输出 slot 2 → 自动转移到背包
 *       - closeScreen
 *       - 清 smeltingFurnacePos
 *
 *   每周期吞吐: 1 ingot / ~210 tick (~10.5s),比旧版本(8 ingots / 25s)慢但完全协议化。
 *   throughput 不足以支撑高强度铁器需求时,用户可调 autoSmeltOres 的 nextInt(500) 节流。
 */
public final class SmeltingBehavior {

	private SmeltingBehavior() {} // 工具类

	/** 熔炉扫描半径,与 EnchantItemTrigger / CraftingBehavior 同思路 */
	private static final int FURNACE_SCAN_RADIUS = 6;
	/** 阶段 2 重开熔炉的最大距离平方(5 格内,服务端 reach 5.5) */
	private static final double COLLECT_DIST_SQ = 25.0;

	/**
	 * 阶段 1: 找熔炉 + 摆原料 + 燃料 + 关界面 + 设倒计时。
	 * 与旧版本一致的节流: 每 ~25s 检查一次。
	 */
	public static void autoSmeltOres(ServerPlayerEntity player) {
		Personality pers = Personality.get(player);
		if (pers == null) return;
		if (pers.smeltingTicks > 0) return;             // 已在阶段 2 等待
		if (pers.smeltingFurnacePos != null) return;    // 阶段 1 已执行待 collect
		if (pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return; // 与合成串行
		if (ThreadLocalRandom.current().nextInt(500) != 0) return;

		PlayerInventory inv = player.getInventory();
		int rawIronSlot = findItemSlot(inv, Items.RAW_IRON);
		if (rawIronSlot < 0) return;
		int fuelSlot = findFuelSlot(inv);
		if (fuelSlot < 0) return;

		BlockPos furnace = findFurnace(player, FURNACE_SCAN_RADIUS);
		if (furnace == null) return;

		// 距离检查 — autoSmeltOres 触发频率低,贴近熔炉时再启动
		if (player.squaredDistanceTo(Vec3d.ofCenter(furnace)) > COLLECT_DIST_SQ) return;

		// 真协议化阶段 1: 开熔炉 → 摆 1 raw_iron + 1 fuel → 关
		if (!placeIngredientsInFurnace(player, furnace, rawIronSlot, fuelSlot)) {
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "place_ingredients_failed", "furnace", furnace);
			return;
		}

		// 标记阶段 2 状态
		pers.smeltingFurnacePos = furnace;
		// vanilla 烧炼周期 200 tick;给点抖动避免与多假人同步
		pers.smeltingTicks = 200 + ThreadLocalRandom.current().nextInt(40);
		com.maohi.fakeplayer.TaskLogger.log(player, "smelt_start",
			"furnace", furnace, "ticks", pers.smeltingTicks);
	}

	/**
	 * 阶段 2 倒计时驱动: 归零时 collect。
	 */
	public static void tickSmelting(ServerPlayerEntity player, Personality pers) {
		if (pers.smeltingTicks <= 0) return;
		pers.smeltingTicks--;

		if (pers.smeltingTicks == 0) {
			BlockPos furnace = pers.smeltingFurnacePos;
			pers.smeltingFurnacePos = null; // 不论成败都清状态,避免卡死

			if (furnace == null) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "furnace_pos_lost");
				return;
			}
			// 假人可能在 200 tick 期间走远了,放弃 collect(产物留在熔炉里,下次再说)
			if (player.squaredDistanceTo(Vec3d.ofCenter(furnace)) > COLLECT_DIST_SQ) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "walked_away", "furnace", furnace);
				return;
			}
			// 熔炉可能被破坏
			if (!isFurnaceBlock(player.getEntityWorld(), furnace)) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "furnace_destroyed", "furnace", furnace);
				return;
			}

			collectFromFurnace(player, furnace);
		}
	}

	// ---- internal: 真协议交互 ----

	private static boolean placeIngredientsInFurnace(ServerPlayerEntity player, BlockPos furnace,
	                                                 int rawIronInvSlot, int fuelInvSlot) {
		// 朝熔炉看 + 真实 interactBlock 包打开 GUI
		Vec3d center = Vec3d.ofCenter(furnace);
		facePoint(player, center);
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, furnace, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		// 校验 FurnaceScreenHandler 已开启
		if (!(player.currentScreenHandler instanceof FurnaceScreenHandler handler)) {
			if (player.currentScreenHandler != player.playerScreenHandler) {
				InventoryActionHelper.closeScreen(player);
			}
			return false;
		}

		int rawIronScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, rawIronInvSlot);
		int fuelScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, fuelInvSlot);
		if (rawIronScreenSlot < 0 || fuelScreenSlot < 0) {
			InventoryActionHelper.closeScreen(player);
			return false;
		}

		// 摆 1 raw_iron → input slot 0
		InventoryActionHelper.moveOneToHandlerSlot(player, rawIronScreenSlot, 0);
		// 摆 1 fuel → fuel slot 1
		InventoryActionHelper.moveOneToHandlerSlot(player, fuelScreenSlot, 1);

		InventoryActionHelper.closeScreen(player);
		return true;
	}

	private static void collectFromFurnace(ServerPlayerEntity player, BlockPos furnace) {
		Vec3d center = Vec3d.ofCenter(furnace);
		facePoint(player, center);
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, furnace, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		if (!(player.currentScreenHandler instanceof FurnaceScreenHandler handler)) {
			if (player.currentScreenHandler != player.playerScreenHandler) {
				InventoryActionHelper.closeScreen(player);
			}
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "screen_not_opened", "furnace", furnace);
			return;
		}

		// QUICK_MOVE 输出 slot 2 (产物) → vanilla AbstractFurnaceScreenHandler.quickMove
		// 自动把产物转移到玩家背包(找空槽或合并同物品)
		InventoryActionHelper.quickMove(player, 2);

		InventoryActionHelper.closeScreen(player);

		// 反馈音效(贴合真人成品出炉的视觉/听觉强化)
		player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
			net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
			net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 0.8f);

		com.maohi.fakeplayer.TaskLogger.log(player, "smelt_done", "furnace", furnace);
	}

	// ---- internal: 工具方法 ----

	/** 找第一个匹配 item 的背包槽位 */
	private static int findItemSlot(PlayerInventory inv, Item item) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	/** 找第一个可烧炼燃料槽位(煤/木炭/各种木类) */
	private static int findFuelSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (stack.isEmpty()) continue;
			if (stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL)
				|| stack.isOf(Items.OAK_LOG) || stack.isOf(Items.BIRCH_LOG)
				|| stack.isOf(Items.SPRUCE_LOG) || stack.isOf(Items.OAK_PLANKS)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isFurnaceBlock(ServerWorld world, BlockPos pos) {
		return world.getBlockState(pos).isOf(Blocks.FURNACE);
	}

	/** 同心壳扫熔炉 — 与 CraftingBehavior.findCraftingTable 同思路。 */
	private static BlockPos findFurnace(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					for (int dy = -3; dy <= 3; dy++) {
						mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						if (world.getBlockState(mut).isOf(Blocks.FURNACE)) {
							return mut.toImmutable();
						}
					}
				}
			}
		}
		return null;
	}

	/** 内联 facePoint(避免依赖 ai/trigger 子包),与 TriggerUtil.facePoint 一致。 */
	private static void facePoint(ServerPlayerEntity player, Vec3d point) {
		double dx = point.x - player.getX();
		double dy = point.y - (player.getY() + 1.62);
		double dz = point.z - player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizDist)));
		player.setYaw(yaw);
		player.setPitch(pitch);
	}
}
