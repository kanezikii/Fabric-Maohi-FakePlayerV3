package com.maohi;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 假人管理命令系统
 * NOTE: 所有命令需要 OP 权限等级 4（最高管理员/控制台）才能使用，以确保隐蔽性
 */
public class MaohiCommands {

    // ===== 性能指标收集器 =====
    private static final AtomicLong totalTickTimeNs = new AtomicLong(0);
    private static final AtomicInteger tickCount = new AtomicInteger(0);
    private static final AtomicInteger spawnCount = new AtomicInteger(0);
    private static final AtomicInteger spawnFailures = new AtomicInteger(0);
    private static final AtomicInteger respawnCount = new AtomicInteger(0);
    private static final AtomicInteger respawnFailures = new AtomicInteger(0);

    /** 动态建议器：提供当前在线的假人名单 */
    private static final SuggestionProvider<ServerCommandSource> ONLINE_BOTS_SUGGESTION = (ctx, builder) -> {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager != null) {
            // 提取纯名字部分 (去掉方括号的任务状态)
            return CommandSource.suggestMatching(
                manager.getOnlinePlayerInfo().values().stream().map(s -> s.split(" ")[0]), 
                builder
            );
        }
        return builder.buildFuture();
    };

    /** 动态建议器：提供历史库中且当前不在线的假人名单 */
    private static final SuggestionProvider<ServerCommandSource> OFFLINE_KNOWN_BOTS_SUGGESTION = (ctx, builder) -> {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager != null) {
            return CommandSource.suggestMatching(
                manager.getKnownPlayers().values().stream()
                    .filter(p -> !manager.isVirtualPlayer(p.uuid))
                    .map(p -> p.name), 
                builder
            );
        }
        return builder.buildFuture();
    };

    /** 记录一次 tick 耗时（由 manageLoop 调用） */
    public static void recordTickTime(long nanos) {
        totalTickTimeNs.addAndGet(nanos);
        tickCount.incrementAndGet();
    }

    /** 记录一次成功生成 */
    public static void recordSpawnSuccess() { spawnCount.incrementAndGet(); }

    /** 记录一次生成失败 */
    public static void recordSpawnFailure() { spawnFailures.incrementAndGet(); }

    /** 记录一次成功复活 */
    public static void recordRespawnSuccess() { respawnCount.incrementAndGet(); }

    /** 记录一次复活失败 */
    public static void recordRespawnFailure() { respawnFailures.incrementAndGet(); }

    /**
     * 注册所有 /maohi 子命令
     * 现在支持直接传入 Dispatcher (由 Mixin 调用)
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        registerCommands(dispatcher);
    }

    /** 兼容旧版调用 (Legacy) */
    public static void register() {
        // 已弃用，逻辑由 CommandManagerMixin 处理
    }

    private static com.maohi.fakeplayer.VirtualPlayerManager requireManager(ServerCommandSource source) {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager == null) {
            source.sendFeedback(() -> Text.of("§c[FS Core] 管理器未初始化"), false);
        }
        return manager;
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("maohi")
                // NOTE: 1.21.11 (1.21.2+) 官方 API 推荐写法
                // 使用 OWNERS_CHECK 对应原 OP 等级 4，避开已移除的 hasPermissionLevel 方法
                .requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))

                // /maohi status — 查看当前状态
                .then(CommandManager.literal("status")
                    .executes(ctx -> {
                        com.maohi.fakeplayer.VirtualPlayerManager manager = requireManager(ctx.getSource());
                        if (manager != null) {
                            ctx.getSource().sendFeedback(() -> Text.of("§6[FS Core] " + manager.getStatusSummary()), false);
                            return 1;
                        }
                        return 0;
                    })
                )

                // /maohi list — 列出所有在线假人
                .then(CommandManager.literal("list")
                    .executes(ctx -> {
                        com.maohi.fakeplayer.VirtualPlayerManager manager = requireManager(ctx.getSource());
                        if (manager == null) return 0;

                        Map<UUID, String> onlinePlayers = manager.getOnlinePlayerInfo();
                        if (onlinePlayers.isEmpty()) {
                            ctx.getSource().sendFeedback(() -> Text.of("§7[FS Core] 当前没有在线的假人"), false);
                            return 0;
                        }

                        ctx.getSource().sendFeedback(() -> Text.of("§6[FS Core] 在线假人列表:"), false);
                        for (Map.Entry<UUID, String> entry : onlinePlayers.entrySet()) {
                            ctx.getSource().sendFeedback(() -> Text.of("  §a" + entry.getValue()), false);
                        }
                        return 1;
                    })
                )

                // /maohi spawn <name> — 手动生成指定名字的假人
                .then(CommandManager.literal("spawn")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(OFFLINE_KNOWN_BOTS_SUGGESTION)
                        .executes(ctx -> {
                            com.maohi.fakeplayer.VirtualPlayerManager manager = requireManager(ctx.getSource());
                            if (manager == null) return 0;

                            String name = StringArgumentType.getString(ctx, "name");
                            boolean success = manager.spawnNamedPlayer(name);
                            if (success) {
                                ctx.getSource().sendFeedback(() -> Text.of("§a[FS Core] 已生成假人: " + name), false);
                            } else {
                                ctx.getSource().sendFeedback(() -> Text.of("§c[FS Core] 生成失败（可能已存在同名玩家）"), false);
                            }
                            return success ? 1 : 0;
                        })
                    )
                )

                // /maohi kick <name> — 踢出指定假人
                .then(CommandManager.literal("kick")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(ONLINE_BOTS_SUGGESTION)
                        .executes(ctx -> {
                            com.maohi.fakeplayer.VirtualPlayerManager manager = requireManager(ctx.getSource());
                            if (manager == null) return 0;

                            String name = StringArgumentType.getString(ctx, "name");
                            boolean success = manager.kickNamedPlayer(name);
                            if (success) {
                                ctx.getSource().sendFeedback(() -> Text.of("§a[FS Core] 已踢出假人: " + name), false);
                            } else {
                                ctx.getSource().sendFeedback(() -> Text.of("§c[FS Core] 未找到该假人: " + name), false);
                            }
                            return success ? 1 : 0;
                        })
                    )
                )

                // /maohi reload — 重载配置
                .then(CommandManager.literal("reload")
                    .executes(ctx -> {
                        MaohiConfig.reload();
                        ctx.getSource().sendFeedback(() -> Text.of("§a[FS Core] 配置已重载"), false);
                        return 1;
                    })
                )
                
                // /maohi on — 开启系统
                .then(CommandManager.literal("on")
                    .executes(ctx -> {
                        MaohiConfig.getInstance().botEnabled = true;
                        ctx.getSource().sendFeedback(() -> Text.of("§a[FS Core] 假人系统已启用"), false);
                        return 1;
                    })
                )
                
                // /maohi off — 关闭系统
                .then(CommandManager.literal("off")
                    .executes(ctx -> {
                        MaohiConfig.getInstance().botEnabled = false;
                        ctx.getSource().sendFeedback(() -> Text.of("§c[FS Core] 假人系统已禁用，正在清场..."), false);
                        return 1;
                    })
                )

                // /maohi metrics — 性能指标
                .then(CommandManager.literal("metrics")
                    .executes(ctx -> {
                        int ticks = tickCount.get();
                        long totalNs = totalTickTimeNs.get();
                        double avgMs = ticks > 0 ? (totalNs / (double) ticks) / 1_000_000.0 : 0;

                        ctx.getSource().sendFeedback(() -> Text.of("§6[FS Core] 性能指标:"), false);
                        ctx.getSource().sendFeedback(() -> Text.of(String.format(
                            "  §7Tick 平均耗时: §f%.3fms §7(共 %d 次)", avgMs, ticks)), false);
                        ctx.getSource().sendFeedback(() -> Text.of(String.format(
                            "  §7生成: §a%d 成功 §c%d 失败", spawnCount.get(), spawnFailures.get())), false);
                        ctx.getSource().sendFeedback(() -> Text.of(String.format(
                            "  §7复活: §a%d 成功 §c%d 失败", respawnCount.get(), respawnFailures.get())), false);
                        return 1;
                    })
                )
        );
    }
}
