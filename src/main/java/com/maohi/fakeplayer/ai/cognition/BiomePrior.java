package com.maohi.fakeplayer.ai.cognition;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1: 生物群系先验知识 — 让假人对「哪个 biome 有什么资源」有常识级别的判断。
 *
 * 设计哲学:
 *   假人不应该在沙漠里绝望地扫描木头，也不应该在茂密森林里感叹找不到矿。
 *   这张表让 setExplore 在选择目标方向时，优先朝「有资源」的 biome 类型走。
 *
 * 实现策略:
 *   - 使用 vanilla BiomeTags（IS_FOREST / IS_SAVANNA / IS_JUNGLE / IS_DESERT 等）
 *     而不是穷举 biome ID，这样对 Mod 新 biome 天然兼容。
 *   - 评分是相对值：正数=亲和，0=中立，负数=回避。
 *   - 不做精确寻路，只影响 setExplore 采样时的「加权偏向」。
 *
 * 红线(不能做的事):
 *   - 不能让 bot 径直冲向某个 biome 的精确坐标（那是 GPS 上帝视角）。
 *   - 只影响「朝哪个方向探索」的概率分布，最终还是靠扫描找资源。
 */
public final class BiomePrior {

    // ============================================================
    // V5.62: chunk 级 affinity 缓存 — biome 在 chunk 内永不变化,
    //   缓存命中即 O(1) 返回,根本不调 world.getBiome。
    //
    // 背景:
    //   ea204f2 之前发现 BiomePrior.getAffinity 调 world.getBiome 在主线程触发
    //   ServerChunkManager.getChunk(create=true) 同步等待,卡顿 1~50s。
    //   ea204f2 加了 3x3 isChunkLoaded guard,但仍然抓到 1131ms thread_stall
    //   (2026-05-27 stack: BiomePrior.getAffinity:52 → getBiome → getChunk → park)。
    //   原因:vanilla world.isChunkLoaded 状态不够严格,且 BiomeAccess 的 noise
    //   jittered sampling 偏移可能越界到第 2 圈邻居 chunk。
    //
    // 修法:
    //   - 缓存命中(占大多数,bot 长期停在同一 chunk)直接返回,绕过整条同步路径。
    //   - 缓存未命中走严格 isChunkReady (PathfindingNavigation mixin,O(1) 非阻塞)
    //     全部 9 块 ready 才调 world.getBiome,否则返中立 0。
    //   - 缓存条目用 SENTINEL 标记"未算过该 ResourceType",避免 0 与"未填充"歧义。
    // ============================================================
    private static final ConcurrentHashMap<Long, int[]> AFFINITY_CACHE = new ConcurrentHashMap<>();
    /** 缓存上限:满则整体清空(simple LRU 不值得这复杂度)。4096 chunk ≈ 1024×1024 方块覆盖 */
    private static final int CACHE_LIMIT = 4096;
    private static final int SENTINEL = Integer.MIN_VALUE;

    /**
     * 从玩家当前站立位置评估该 biome 对「目标资源」的亲和度。
     *
     * @param player   当前假人
     * @param resource 需要的资源类型
     * @return 亲和度分数：+2=非常好，+1=还行，0=中立，-1=不好，-2=基本没有
     */
    public static int getAffinity(ServerPlayerEntity player, ResourceType resource) {
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();
        return getAffinityCached(world, pos.getX() >> 4, pos.getZ() >> 4, pos, resource);
    }

    /**
     * 评估目标区域(tx, tz)的 biome 对资源的亲和度（用于远征目标选择）。
     * chunk 未加载时回退到 0（中立），不阻塞主线程。
     */
    public static int getAffinityAt(ServerWorld world, int tx, int tz, int ty, ResourceType resource) {
        return getAffinityCached(world, tx >> 4, tz >> 4, new BlockPos(tx, ty, tz), resource);
    }

    private static int getAffinityCached(ServerWorld world, int cx, int cz, BlockPos pos, ResourceType resource) {
        long key = ChunkPos.toLong(cx, cz);
        int[] cached = AFFINITY_CACHE.get(key);
        int idx = resource.ordinal();
        if (cached != null && cached[idx] != SENTINEL) {
            return cached[idx];
        }
        // 缓存未命中 → 严格非阻塞 chunk-ready 检查(mixin O(1) FULL 状态),不阻塞主线程
        if (!isBiomeSampleReady(world, cx, cz)) return 0;
        try {
            RegistryEntry<Biome> biomeEntry = world.getBiome(pos);
            int score = computeAffinity(biomeEntry, resource);
            int[] arr = cached;
            if (arr == null) {
                if (AFFINITY_CACHE.size() >= CACHE_LIMIT) AFFINITY_CACHE.clear();
                arr = new int[ResourceType.values().length];
                java.util.Arrays.fill(arr, SENTINEL);
                int[] prev = AFFINITY_CACHE.putIfAbsent(key, arr);
                if (prev != null) arr = prev;
            }
            arr[idx] = score;
            return score;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * BiomeAccess.getBiome 内部 noise jittered sampling 偏移最大约 ±8 方块,
     * 在 chunk 边界附近会越界采样相邻 chunk。3x3 全部 ready 才安全调 world.getBiome。
     * 用 PathfindingNavigation.isChunkReady (mixin getChunkHolder → ChunkHolder.getWorldChunk)
     * 替代 vanilla world.isChunkLoaded,前者是 O(1) 严格状态查,后者在主线程仍可能 pump 任务队列。
     */
    private static boolean isBiomeSampleReady(ServerWorld world, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 快速判断：当前 biome 是否「几乎肯定没有」某种资源。
     * 返回 true 时 setExplore 会更积极地跳过当前方向，去别处找。
     */
    public static boolean isHostile(ServerPlayerEntity player, ResourceType resource) {
        return getAffinity(player, resource) <= -2;
    }

    // ==================== 核心亲和度计算 ====================

    private static int computeAffinity(RegistryEntry<Biome> biomeEntry, ResourceType resource) {
        return switch (resource) {
            case LOG   -> logAffinity(biomeEntry);
            case STONE -> stoneAffinity(biomeEntry);
            case IRON  -> ironAffinity(biomeEntry);
            case COAL  -> coalAffinity(biomeEntry);
            case FOOD  -> foodAffinity(biomeEntry);
        };
    }

    /**
     * 木头亲和度 — 哪里有树。
     * 森林/丛林/针叶/沼泽 → 高；草原/山地 → 中；沙漠/海洋/冰原 → 极低
     */
    private static int logAffinity(RegistryEntry<Biome> b) {
        if (b.isIn(BiomeTags.IS_JUNGLE))  return 2;   // 丛林：树的天堂
        if (b.isIn(BiomeTags.IS_FOREST))  return 2;   // 森林：标准产木地
        if (b.isIn(BiomeTags.IS_SAVANNA)) return 1;   // 热带草原：有金合欢树
        if (b.isIn(BiomeTags.IS_TAIGA))   return 2;   // 针叶林：松树/云杉
        if (b.isIn(BiomeTags.IS_BADLANDS)) return -1; // 恶地：几乎没树
        // NOTE: IS_DESERT 在 1.21.x Fabric Yarn 中不存在，沙漠 biome 以下标签均不匹配
        //   → fallthrough 到 return 0（中立）实际上偏保守，但不会编译错误
        if (b.isIn(BiomeTags.IS_OCEAN))   return -2;  // 海洋：没树
        if (b.isIn(BiomeTags.IS_NETHER))  return -2;  // 下界：没树
        if (b.isIn(BiomeTags.IS_MOUNTAIN)) return 0;  // 山地：少量树
        return 0; // 草原/沙漠/其他：中立
    }

    /**
     * 石头亲和度 — 哪里能挖到石头。
     * 山地/恶地/河岸裸露 → 高；深洋/沙漠覆沙 → 中（地下有）；下界 → 无（只有圆石岩）
     */
    private static int stoneAffinity(RegistryEntry<Biome> b) {
        if (b.isIn(BiomeTags.IS_MOUNTAIN)) return 2;   // 山：裸露石头多
        if (b.isIn(BiomeTags.IS_BADLANDS)) return 2;   // 恶地：裸露红岩
        if (b.isIn(BiomeTags.IS_NETHER))   return -2;  // 下界：没有石头
        if (b.isIn(BiomeTags.IS_OCEAN))    return 0;   // 海底有石头但难挖
        return 1; // 大部分陆地 biome：挖一挖都有石头
    }

    /**
     * 铁矿亲和度 — 地表可见铁矿或容易到达铁矿层。
     * 山地/恶地：地形低 → 容易挖到；沙漠/草原：地面平 → 需要往下挖（中立）；下界：没有
     */
    private static int ironAffinity(RegistryEntry<Biome> b) {
        if (b.isIn(BiomeTags.IS_MOUNTAIN)) return 2;  // 山壁上经常能看到裸露铁矿
        if (b.isIn(BiomeTags.IS_BADLANDS)) return 1;  // 恶地峡谷有裸露矿
        if (b.isIn(BiomeTags.IS_NETHER))   return -2; // 下界没有铁矿
        if (b.isIn(BiomeTags.IS_END))      return -2; // 末地没有铁矿
        return 0; // 其他陆地：铁矿在地下都有，biome 关联度低
    }

    /**
     * 煤炭亲和度 — 煤炭矿石分布。
     * 和铁矿类似，山地裸露更多。
     */
    private static int coalAffinity(RegistryEntry<Biome> b) {
        if (b.isIn(BiomeTags.IS_MOUNTAIN)) return 2;
        if (b.isIn(BiomeTags.IS_NETHER))   return -2;
        if (b.isIn(BiomeTags.IS_END))      return -2;
        return 0;
    }

    /**
     * 食物亲和度 — 哪里能找到食物来源（动物/浆果/蘑菇）。
     * 草原/森林：动物多；沙漠/下界/末地：几乎没有
     */
    private static int foodAffinity(RegistryEntry<Biome> b) {
        if (b.isIn(BiomeTags.IS_SAVANNA))  return 2;  // 牛羊大量
        if (b.isIn(BiomeTags.IS_FOREST))   return 1;  // 猪/鸡/浆果
        if (b.isIn(BiomeTags.IS_TAIGA))    return 1;  // 甜浆果/狐狸
        // NOTE: IS_DESERT 在 1.21.x Fabric Yarn 中不存在，沙漠 fallthrough → return 0
        if (b.isIn(BiomeTags.IS_NETHER))   return -1; // 下界：猪灵/疣猪兽
        if (b.isIn(BiomeTags.IS_OCEAN))    return -1; // 海：只有鱼
        if (b.isIn(BiomeTags.IS_END))      return -2; // 末地：没食物
        return 0;
    }

    /** 所有当前支持的资源类型 */
    public enum ResourceType {
        LOG,   // 木头/原木
        STONE, // 石头/圆石
        IRON,  // 铁矿
        COAL,  // 煤炭
        FOOD   // 食物来源
    }
}
