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
 * 假人社交引擎 (V3.10 极简重构版)
 * 职责：调度发言请求、统一聊天出口、处理冷却逻辑。
 */
public class SocialEngine {
    private final VirtualPlayerManager manager;
    private final List<SocialResponse> pendingResponses = new CopyOnWriteArrayList<>();
    private long nextAvailableChatTime = 0;
    private long lastScheduledTime = 0;

    public SocialEngine(VirtualPlayerManager manager) {
        this.manager = manager;
    }

    public void onChatMessage(ServerPlayerEntity sender, String content) {
        if (sender.networkHandler.connection instanceof FakeClientConnection || manager.isVirtualPlayer(sender.getUuid())) return;
        
        if (content.toLowerCase().matches(".*(hi|hello|yo|hey).*")) {
            for (UUID id : manager.getOnlinePlayerUuids()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
                VirtualPlayerManager.Personality personality = manager.getPersonality(id);
                
                if (p != null && personality != null && !personality.farewellSaid && p.squaredDistanceTo(sender) < 225 
                    && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.NEARBY_GREET_COOLDOWN) {
                    
                    String resp = VocabularyBank.getGreeting(sender.getName().getString());
                    scheduleDelayedResponse(new String[]{resp}, 1, 3, id);
                    personality.lastCommandTime = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    public void onPlayerDeathNearby(ServerPlayerEntity victim) {
        for (UUID id : manager.getOnlinePlayerUuids()) {
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            VirtualPlayerManager.Personality personality = manager.getPersonality(id);
            
            if (p != null && p.squaredDistanceTo(victim) < 100 && ThreadLocalRandom.current().nextInt(100) < 30) {
                if (personality != null && !personality.farewellSaid && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.FAREWELL_LOCK_DURATION) {
                    String reaction = VocabularyBank.getDeathReaction(victim.getName().getString());
                    scheduleDelayedResponse(new String[]{reaction}, 2, 4, id);
                    personality.lastCommandTime = System.currentTimeMillis();
                }
            }
        }
    }

    public void onVictimDeath(UUID victim) {
        if (manager.isLoggingOut(victim) || ThreadLocalRandom.current().nextInt(100) >= 70) return;
        scheduleDelayedResponse(new String[]{VocabularyBank.getCombatLose()}, 3, 6, victim);
    }

    public boolean isGlobalChatAvailable() {
        long now = System.currentTimeMillis();
        return now >= nextAvailableChatTime && (now - lastScheduledTime > 5000L);
    }

    public void scheduleDelayedResponse(String[] pool, int minSec, int maxSec, UUID sender) {
        if (manager.isLoggingOut(sender)) return;
        lastScheduledTime = System.currentTimeMillis();

        String msg = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        long totalDelay = (minSec * 1000L) + ThreadLocalRandom.current().nextLong((maxSec - minSec) * 1000L) + (msg.length() * 200L);
        pendingResponses.add(new SocialResponse(sender, msg, System.currentTimeMillis() + totalDelay));
    }

    public synchronized void tick(long nowMs) {
        pendingResponses.removeIf(resp -> {
            if (nowMs < resp.sendAt) return false;
            
            // 核心冷却检查
            if (nowMs < nextAvailableChatTime) return false;

            // 既然满足条件，立即关门，执行发送
            sendImmediateChat(resp.sender, resp.message);
            nextAvailableChatTime = nowMs + 20000L;
            return true;
        });
    }

    /**
     * V5.5 唯一物理出口
     */
    public void sendImmediateChat(UUID uuid, String message) {
        if (manager.isLoggingOut(uuid)) return;
        
        try {
            // 1. 优先获取插件缓存的名字
            String name = manager.getVirtualPlayerName(uuid);
            // 2. 兜底获取原版名字
            if (name == null || name.isEmpty()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);
                if (p != null) name = p.getName().getString();
            }
            
            if (name == null || name.isEmpty()) return;

            // 3. 强制物理拼接，确保不匿名
            String formatted = "<" + name + "> " + message.trim();
            
            manager.getServer().getPlayerManager().broadcast(Text.literal(formatted), false);
            org.slf4j.LoggerFactory.getLogger("Server thread").info(formatted);
        } catch (Exception ignored) {}
    }

    private static record SocialResponse(UUID sender, String message, long sendAt) {}
}
