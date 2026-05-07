package com.maohi.fakeplayer;

import com.maohi.MaohiConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V5.30 任务系统调试日志器。
 *
 * 启用方式:`mods/server-util.json` 内 `"debugVirtualTasks": true` → 重启或热重载配置后生效。
 * 关闭后 enabled() 直接 return false,所有 log() 调用立即早返,几乎零成本。
 *
 * 输出格式: [MaohiTask] [<botName>] <event> k1=v1 k2=v2 ...
 * 例: [MaohiTask] [Steve_24] assign phase=STONE_AGE task=WOODCUTTING target=BlockPos{x=12,y=64,z=-7}
 *
 * 全部走 "Server thread" SLF4J 通道(与项目其它日志一致),tail 一个文件就能看完。
 */
public final class TaskLogger {

	private TaskLogger() {}

	private static final Logger LOG = LoggerFactory.getLogger("Server thread");
	private static final String PREFIX = "[MaohiTask] ";

	/** 单一开关查询 — config 是 volatile 单例,无锁读 */
	public static boolean enabled() {
		try {
			MaohiConfig cfg = MaohiConfig.getInstance();
			return cfg != null && cfg.debugVirtualTasks;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * 主入口:按 player + event + 交替 k/v 输出。
	 * kvPairs 必须成对,奇数个会丢掉末尾。null player 退化为 "?"。
	 */
	public static void log(ServerPlayerEntity player, String event, Object... kvPairs) {
		if (!enabled()) return;
		String name = (player != null && player.getName() != null) ? player.getName().getString() : "?";
		LOG.info(format(name, event, kvPairs));
	}

	/** 没有 player handle 的边角场景(uuid 已注销 / pre-spawn)用名字字符串直接打 */
	public static void logRaw(String botName, String event, Object... kvPairs) {
		if (!enabled()) return;
		LOG.info(format(botName != null ? botName : "?", event, kvPairs));
	}

	private static String format(String name, String event, Object[] kv) {
		StringBuilder sb = new StringBuilder(64 + (kv == null ? 0 : kv.length * 16));
		sb.append(PREFIX).append('[').append(name).append("] ").append(event);
		if (kv != null) {
			for (int i = 0; i + 1 < kv.length; i += 2) {
				sb.append(' ').append(kv[i]).append('=').append(stringify(kv[i + 1]));
			}
		}
		return sb.toString();
	}

	/** null → "null";其它走 String.valueOf。BlockPos 默认 toString 已经够用。 */
	private static String stringify(Object o) {
		return o == null ? "null" : String.valueOf(o);
	}
}
