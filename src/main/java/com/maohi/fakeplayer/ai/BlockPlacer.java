package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.mixin.PlayerInventoryAccessor;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 方块放置模拟器 (V3)
 *
 * V5.23 修复:
 *   原实现 tryPlaceTorch 在同一 tick 内连续打 4 个包(切槽→交互→挥手→切回原槽位),
 *   反作弊看到的就是"0ms 切槽 + 0ms 切回"——任何检测 hotbar swap 频率的插件都会报警。
 *   真实客户端切换 hotbar 至少要 1 tick 渲染 + 玩家手指反应 ≈ 100~200ms,使用方块再切回
 *   通常需要 200~400ms。
 *
 *   改成 3 阶段状态机,跨 tick 推进:
 *     stage 0 → 1: 切到火把槽,记录 placeAtTick = now + 3~6 tick
 *     stage 1 → 2: 到达 placeAtTick,interactBlock+swing,记录 restoreAtTick = now + 4~8 tick
 *     stage 2 → 0: 到达 restoreAtTick,切回原槽位,完成。
 *
 *   过程中如果 stage>0 立刻退出,不会重复触发。
 */
public class BlockPlacer {

	/** 放置阶段相邻包之间的最小 tick 间隔(50ms/tick) */
	private static final int PLACE_DELAY_MIN = 3;   // 150ms
	private static final int PLACE_DELAY_MAX = 6;   // 300ms
	private static final int RESTORE_DELAY_MIN = 4; // 200ms
	private static final int RESTORE_DELAY_MAX = 8; // 400ms

	/**
	 * 检查并尝试放置火把(状态机 tick)
	 * 触发条件: 正在挖矿/探索 + 环境太暗 + 包里有火把
	 * VPM 每 tick 调用一次。
	 */
	public static void tryPlaceTorch(ServerPlayerEntity player, Personality personality) {
		long now = player.getEntityWorld().getTime();

		// 状态机推进:已经在某个阶段中 → 不重新发起,只推进
		if (personality.torchPlaceStage > 0) {
			advanceTorchStateMachine(player, personality, now);
			return;
		}

		// 1. 只有挖矿或探索状态才会插火把
		if (personality.currentTask != TaskType.MINING &&
			personality.currentTask != TaskType.EXPLORING) {
			return;
		}

		// 2. 频率控制:每 tick 5% 概率检查,避免密密麻麻全是火把
		if (ThreadLocalRandom.current().nextInt(20) != 0) return;

		BlockPos pos = player.getBlockPos();

		// 3. 亮度判定:低于 7 才插火把
		int lightLevel = player.getEntityWorld().getLightLevel(LightType.BLOCK, pos);
		if (lightLevel >= 7) return;
		// 露天且白天不需要插火把
		if (player.getEntityWorld().getLightLevel(LightType.SKY, pos) > 10 && player.getEntityWorld().isDay()) {
			return;
		}

		// 4. 检查快捷栏 (0-8) 是否有火把
		PlayerInventory inv = player.getInventory();
		int torchSlot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.TORCH)) {
				torchSlot = i;
				break;
			}
		}
		// 包里没火把就摸黑挖,真人也这样
		if (torchSlot == -1) return;

		// 5. 目标方块:假人脚下 — 必须是非空气才能放火把
		BlockPos blockUnder = pos.down();
		if (player.getEntityWorld().getBlockState(blockUnder).isAir()) return;

		// 6. 已经在脚下或当前手就是火把 → 直接走 stage 1 略过切槽
		int currentSlot = ((PlayerInventoryAccessor) inv).getSelectedSlot();

		// === stage 0 → 1: 切到火把槽,推进到等待放置阶段 ===
		personality.torchOriginalSlot = currentSlot;
		personality.torchTargetSlot = torchSlot;
		personality.torchPlaceBlockPos = blockUnder;
		personality.torchPlaceAtTick = now + PLACE_DELAY_MIN
			+ ThreadLocalRandom.current().nextInt(PLACE_DELAY_MAX - PLACE_DELAY_MIN + 1);
		personality.torchPlaceStage = 1;

		// 当前手已经是火把(currentSlot == torchSlot)就跳过发切槽包,但 stage 仍走流程
		if (currentSlot != torchSlot) {
			PacketHelper.setSelectedSlot(player, torchSlot);
		}
	}

	/**
	 * V5.23: 推进火把放置状态机的下一步。
	 * 在 stage>0 时被调用;到达计划时间才执行对应包,否则直接 return。
	 */
	private static void advanceTorchStateMachine(ServerPlayerEntity player, Personality personality, long now) {
		// === stage 1 → 2: 到达放置时刻,执行 interactBlock + 挥手 ===
		if (personality.torchPlaceStage == 1 && now >= personality.torchPlaceAtTick) {
			BlockPos blockUnder = personality.torchPlaceBlockPos;
			if (blockUnder == null) {
				resetTorchState(personality);
				return;
			}
			// 校验目标方块仍然合法(假人可能已经走开/方块被破坏)
			if (player.getEntityWorld().getBlockState(blockUnder).isAir()) {
				resetTorchState(personality);
				return;
			}
			// 校验槽位仍然有火把(可能被消耗光了)
			ItemStack target = player.getInventory().getStack(personality.torchTargetSlot);
			if (target.isEmpty() || !target.isOf(Items.TORCH)) {
				resetTorchState(personality);
				return;
			}
			BlockHitResult hit = new BlockHitResult(
				Vec3d.ofCenter(blockUnder).add(0, 0.5, 0),
				Direction.UP,
				blockUnder,
				false
			);
			PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
			PacketHelper.swingHand(player, Hand.MAIN_HAND);

			personality.torchRestoreAtTick = now + RESTORE_DELAY_MIN
				+ ThreadLocalRandom.current().nextInt(RESTORE_DELAY_MAX - RESTORE_DELAY_MIN + 1);
			personality.torchPlaceStage = 2;
			return;
		}

		// === stage 2 → 0: 切回原槽位 ===
		if (personality.torchPlaceStage == 2 && now >= personality.torchRestoreAtTick) {
			int original = personality.torchOriginalSlot;
			int currentSlot = ((PlayerInventoryAccessor) player.getInventory()).getSelectedSlot();
			if (original != currentSlot) {
				PacketHelper.setSelectedSlot(player, original);
			}
			resetTorchState(personality);
		}
	}

	private static void resetTorchState(Personality p) {
		p.torchPlaceStage = 0;
		p.torchOriginalSlot = 0;
		p.torchTargetSlot = 0;
		p.torchPlaceBlockPos = null;
		p.torchPlaceAtTick = 0L;
		p.torchRestoreAtTick = 0L;
	}

	/**
	 * V5.30 W2S: 把背包里的 CRAFTING_TABLE 落到地上。
	 *
	 * 配合 CraftingBehavior.autoCraftStoneTools 的工作台合成路径——
	 *   合成完后 table 在背包,但所有需要工作台的合成(stick/wooden_pickaxe/...)都看 world block,
	 *   没人放就死循环。这里在每 tick 由 VPM 调用,扫到上述状态就启动状态机把它放下来。
	 *
	 * 节奏与 tryPlaceTorch 一致(切槽 → 等 3-6 tick → interactBlock → 等 4-8 tick → 切回),
	 * 用独立 tablePlace* 字段避免与火把竞用。
	 *
	 * 触发条件:
	 *   - currentTask 为 IDLE/MINING/WOODCUTTING/EXPLORING(战斗/吃饭/PVP/合成中不放)
	 *   - currentScreenHandler 是 PlayerScreenHandler(没开任何 GUI)
	 *   - 背包有 CRAFTING_TABLE
	 *   - 周围 6 格无工作台(否则没必要)
	 *   - 脚边四方向有"空气格 + 下方非空气"的可放位
	 */
	public static void tryPlaceCraftingTable(ServerPlayerEntity player, Personality personality) {
		long now = player.getEntityWorld().getTime();

		// 已经在状态机里 → 推进
		if (personality.tablePlaceStage > 0) {
			advanceTablePlaceStateMachine(player, personality, now);
			return;
		}

		// 当前任务白名单
		if (personality.currentTask != TaskType.IDLE
			&& personality.currentTask != TaskType.MINING
			&& personality.currentTask != TaskType.WOODCUTTING
			&& personality.currentTask != TaskType.EXPLORING) {
			return;
		}

		// GUI 阻断:任何容器/合成界面打开时都跳过(避免和 CraftingBehavior 抢 ClickSlot 节拍)
		if (player.currentScreenHandler != player.playerScreenHandler) return;

		// 战斗/吃饭/切磋中不放
		if (personality.isEating || personality.isSparring) return;

		// 频率控制:每 tick 5% 概率检查,避免每 tick 全量扫描
		if (ThreadLocalRandom.current().nextInt(20) != 0) return;

		// 周围 6 格已经有工作台 → 不需要再放
		if (findCraftingTableNearby(player, 6)) return;

		// 包里找 CRAFTING_TABLE 槽位
		PlayerInventory inv = player.getInventory();
		int tableSlot = -1;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.CRAFTING_TABLE)) {
				tableSlot = i;
				break;
			}
		}
		if (tableSlot == -1) return;

		// CRAFTING_TABLE 必须在 hotbar 才能 setSelectedSlot;在背包里的话先靠 InventorySimulator
		// 自然轮换换上来(真人也是先把工作台换到快捷栏再放),这里只在已上 hotbar 的回合放置。
		if (tableSlot > 8) return;

		// 找一个脚边的可放位置:北/南/东/西四面相邻一格,要求 (空气 + 下方非空气)
		BlockPos foot = player.getBlockPos();
		Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
		BlockPos placeAt = null;
		BlockPos supportPos = null;
		Direction faceDir = null;
		for (Direction d : dirs) {
			BlockPos cand = foot.offset(d);
			if (!player.getEntityWorld().getBlockState(cand).isAir()) continue;
			BlockPos under = cand.down();
			if (player.getEntityWorld().getBlockState(under).isAir()) continue;
			placeAt = cand;
			supportPos = under;        // 右键脚边格的下方实心面(face = UP)
			faceDir = Direction.UP;
			break;
		}
		if (placeAt == null) return;

		// === stage 0 → 1: 切到工作台槽 ===
		int currentSlot = ((PlayerInventoryAccessor) inv).getSelectedSlot();
		personality.tableOriginalSlot = currentSlot;
		personality.tableTargetSlot = tableSlot;
		personality.tablePlaceBlockPos = placeAt;
		personality.tablePlaceSupportPos = supportPos;
		personality.tablePlaceFaceDir = faceDir;
		personality.tablePlaceAtTick = now + PLACE_DELAY_MIN
			+ ThreadLocalRandom.current().nextInt(PLACE_DELAY_MAX - PLACE_DELAY_MIN + 1);
		personality.tablePlaceStage = 1;

		if (currentSlot != tableSlot) {
			PacketHelper.setSelectedSlot(player, tableSlot);
		}
	}

	private static void advanceTablePlaceStateMachine(ServerPlayerEntity player, Personality personality, long now) {
		// stage 1 → 2: 到时执行 interactBlock + swing
		if (personality.tablePlaceStage == 1 && now >= personality.tablePlaceAtTick) {
			BlockPos placeAt = personality.tablePlaceBlockPos;
			BlockPos support = personality.tablePlaceSupportPos;
			Direction face = personality.tablePlaceFaceDir;
			if (placeAt == null || support == null || face == null) {
				resetTablePlaceState(personality);
				return;
			}
			// 目标格仍要是空气
			if (!player.getEntityWorld().getBlockState(placeAt).isAir()) {
				resetTablePlaceState(personality);
				return;
			}
			// 槽位仍要有 CRAFTING_TABLE
			ItemStack target = player.getInventory().getStack(personality.tableTargetSlot);
			if (target.isEmpty() || !target.isOf(Items.CRAFTING_TABLE)) {
				resetTablePlaceState(personality);
				return;
			}

			// 朝 support 块对应面看(face 是 UP → 对准 support 块上表面中心)
			Vec3d hitCenter = Vec3d.ofCenter(support).add(
				face.getOffsetX() * 0.5,
				face.getOffsetY() * 0.5,
				face.getOffsetZ() * 0.5
			);
			double dx = hitCenter.x - player.getX();
			double dy = hitCenter.y - (player.getY() + 1.62);
			double dz = hitCenter.z - player.getZ();
			double horizDist = Math.sqrt(dx * dx + dz * dz);
			player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
			player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horizDist)));

			BlockHitResult hit = new BlockHitResult(hitCenter, face, support, false);
			PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
			PacketHelper.swingHand(player, Hand.MAIN_HAND);

			personality.tableRestoreAtTick = now + RESTORE_DELAY_MIN
				+ ThreadLocalRandom.current().nextInt(RESTORE_DELAY_MAX - RESTORE_DELAY_MIN + 1);
			personality.tablePlaceStage = 2;
			return;
		}

		// stage 2 → 0: 切回原槽
		if (personality.tablePlaceStage == 2 && now >= personality.tableRestoreAtTick) {
			int original = personality.tableOriginalSlot;
			int currentSlot = ((PlayerInventoryAccessor) player.getInventory()).getSelectedSlot();
			if (original != currentSlot) {
				PacketHelper.setSelectedSlot(player, original);
			}
			resetTablePlaceState(personality);
		}
	}

	private static void resetTablePlaceState(Personality p) {
		p.tablePlaceStage = 0;
		p.tableOriginalSlot = 0;
		p.tableTargetSlot = 0;
		p.tablePlaceBlockPos = null;
		p.tablePlaceSupportPos = null;
		p.tablePlaceFaceDir = null;
		p.tablePlaceAtTick = 0L;
		p.tableRestoreAtTick = 0L;
	}

	/** 切比雪夫距离 d 由近到远扫,Y±3,与 CraftingBehavior.findCraftingTable 一致。 */
	private static boolean findCraftingTableNearby(ServerPlayerEntity player, int radius) {
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					for (int dy = -3; dy <= 3; dy++) {
						mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						if (world.getBlockState(mut).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) return true;
					}
				}
			}
		}
		return false;
	}
}
