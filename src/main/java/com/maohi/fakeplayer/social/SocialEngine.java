package com.maohi.fakeplayer.social;

import com.maohi.fakeplayer.FakeClientConnection;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人社交引擎中枢 (V3 社交引擎)
 * 统筹所有的社交行为，包括聊天模拟、打字机延迟、环境感应反馈。
 */
public class SocialEngine {
    private final VirtualPlayerManager manager;
    private final List<SocialResponse> pendingResponses = new CopyOnWriteArrayList<>();
    private long nextAvailableChatTime = 0;
    private long lastScheduledTime = 0; // V3.5: 调度锁，防止同一秒内多个假人并发调度同一环境吐槽

    public SocialEngine(VirtualPlayerManager manager) {
        this.manager = manager;
    }

    /**
     * 当有真实玩家在聊天频道说话时触发
     */
    public void onChatMessage(ServerPlayerEntity sender, String content) {
        // 极致拦截：如果是假人发的，或者说话人本身就是假人，不回复，防止无限套娃
        if (sender.networkHandler.connection instanceof FakeClientConnection || manager.isVirtualPlayer(sender.getUuid())) return;
        
        // 简单的关键词匹配回复 (后续可对接 AI 接口)
        if (content.toLowerCase().contains("hi") || content.toLowerCase().contains("hello") || content.toLowerCase().contains("yo")) {
            for (UUID id : manager.getOnlinePlayerUuids()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
                VirtualPlayerManager.Personality personality = manager.getPersonality(id);
                
		// 距离 15 格内且通过社交冷却校验的假人才会回复
		if (p != null && personality != null && !personality.farewellSaid && p.squaredDistanceTo(sender) < 225 && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.NEARBY_GREET_COOLDOWN) {
                    // V3.8: 统一获取名字逻辑
                    String senderName = sender.getName().getString(); // 真人名字
                    String resp = generateRealisticMessage("GREETING", senderName, id);
                    
                    scheduleDelayedResponse(new String[]{resp}, 1, 4, id);
                    personality.lastCommandTime = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    /**
     * 当附近有真实玩家死亡时触发
     */
    public void onPlayerDeathNearby(ServerPlayerEntity victim) {
        for (UUID id : manager.getOnlinePlayerUuids()) {
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            VirtualPlayerManager.Personality personality = manager.getPersonality(id);
            
            // 10 格内目睹死亡，30% 概率嘲讽或同情
            if (p != null && p.squaredDistanceTo(victim) < 100 && ThreadLocalRandom.current().nextInt(100) < 30) {
		if (personality != null && !personality.farewellSaid && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.FAREWELL_LOCK_DURATION) {
			String reaction = generateRealisticMessage("DEATH", victim.getName().getString(), id);
                    scheduleDelayedResponse(new String[]{reaction}, 2, 5, id);
                    personality.lastCommandTime = System.currentTimeMillis();
                }
            }
        }
    }

    /**
     * 当假人自己死亡时触发 (发牢骚逻辑)
     */
    public void onVictimDeath(UUID victim) {
        if (manager.isLoggingOut(victim)) return;
        
        // 死亡后发牢骚概率 (70%)
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < 70) {
            String complaint = VocabularyBank.getCombatLose();
            // 延迟 3-6 秒，正好是点击复活后回到出生点说话的时间
            scheduleDelayedResponse(new String[]{complaint}, 3, 6, victim);
        }
    }

    public boolean isGlobalChatAvailable() {
        long now = System.currentTimeMillis();
        // 同时检查：已经发出去的冷却时间，以及刚刚调度的冷却保护
        return now >= nextAvailableChatTime && (now - lastScheduledTime > 5000L);
    }

    public void scheduleDelayedResponse(String[] pool, int minSec, int maxSec, UUID sender) {
        if (manager.isLoggingOut(sender)) return;
        
        // V3.5 核心修复：立即锁定调度时间，防止并发
        lastScheduledTime = System.currentTimeMillis();

        String msg = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        
        // 模拟打字机延迟：每 2 个字符增加 0.5 秒延迟，基础延迟由调用者决定
        long typeDelay = (msg.length() / 2) * 500L;
        long totalDelay = (minSec * 1000L) + ThreadLocalRandom.current().nextLong((maxSec - minSec) * 1000L) + typeDelay;
        
        pendingResponses.add(new SocialResponse(sender, msg, System.currentTimeMillis() + totalDelay));
    }

    public synchronized void tick(long nowMs) {
        pendingResponses.removeIf(resp -> {
            if (nowMs >= resp.sendAt) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(resp.sender);
                if (p != null) {
                    // 全局聊天冷却保护 (V3.2)
                    if (nowMs < nextAvailableChatTime) return false;

                    String finalMessage = resp.message;

                    manager.getServer().execute(() -> {
                        sendImmediateChat(p.getUuid(), finalMessage);
                    });

                    // 发送后，设定下一次允许发言的时间点 (全局 20 秒冷却)
                    nextAvailableChatTime = nowMs + 20000L;
                    return true;
                }
                return true; // 玩家不在线，丢弃该回复
            }
            return false;
        });
    }

    /**
     * 针对特定场景生成真实的聊天内容
     */
    private String generateRealisticMessage(String category, String targetName, UUID senderUuid) {
        switch (category) {
            case "GREETING": return VocabularyBank.getGreeting(targetName);
            case "DEATH": return VocabularyBank.getDeathReaction(targetName);
            default: return "...";
        }
    }

    private static class SocialResponse {
        UUID sender; String message; long sendAt;
        public SocialResponse(UUID s, String m, long t) { this.sender = s; this.message = m; this.sendAt = t; }
    }

    /**
     * V5.5 统一聊天出口：所有假人聊天最终都走这里
     */
    public void sendImmediateChat(UUID uuid, String message) {
        if (manager.isLoggingOut(uuid)) return;
        
        try {
            String name = manager.getVirtualPlayerName(uuid);
            if (name == null || name.isEmpty()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);
                if (p != null) name = p.getName().getString();
            }
            
            if (name == null || name.isEmpty()) return;

            String formatted = "<" + name + "> " + message.trim();
            
            manager.getServer().getPlayerManager().broadcast(Text.literal(formatted), false);
            org.slf4j.LoggerFactory.getLogger("Server thread").info(formatted);
        } catch (Exception ignored) {}
    }
}
