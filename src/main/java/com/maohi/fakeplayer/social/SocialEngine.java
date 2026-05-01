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
import java.util.concurrent.locks.ReentrantLock;

/**
 * 假人社交引擎 (V5.10 RootCause 修复版)
 */
public class SocialEngine {
    private final VirtualPlayerManager manager;
    private final List<SocialResponse> pendingResponses = new CopyOnWriteArrayList<>();
    private final ReentrantLock chatLock = new ReentrantLock(); // 物理同步锁，防止任何并发产生的重复
    
    private long nextAvailableChatTime = 0;
    private long lastScheduledTime = 0;

    public SocialEngine(VirtualPlayerManager manager) {
        this.manager = manager;
    }

    public void onChatMessage(ServerPlayerEntity sender, String content) {
        if (sender.networkHandler.connection instanceof FakeClientConnection || manager.isVirtualPlayer(sender.getUuid())) return;
        
        if (content.toLowerCase().matches(".*(hi|hello|yo|hey).*")) {
            for (UUID id : manager.getOnlinePlayerUuids()) {
                // RootCause 1 修复：如果这个假人已经在排队说话了，跳过它，防止重复
                if (isAlreadyPending(id)) continue;

                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
                VirtualPlayerManager.Personality personality = manager.getPersonality(id);
                
                if (p != null && personality != null && !personality.farewellSaid && p.squaredDistanceTo(sender) < 225 
                    && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.NEARBY_GREET_COOLDOWN) {
                    
                    String resp = VocabularyBank.getGreeting(sender.getName().getString());
                    scheduleDelayedResponse(new String[]{resp}, 1, 3, id);
                    personality.lastCommandTime = System.currentTimeMillis();
                    break; // 每次只允许一个假人抢答
                }
            }
        }
    }

    public void onPlayerDeathNearby(ServerPlayerEntity victim) {
        for (UUID id : manager.getOnlinePlayerUuids()) {
            if (isAlreadyPending(id)) continue; // 防止重复

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
        if (manager.isLoggingOut(victim) || isAlreadyPending(victim)) return;
        if (ThreadLocalRandom.current().nextInt(100) < 70) {
            scheduleDelayedResponse(new String[]{VocabularyBank.getCombatLose()}, 3, 6, victim);
        }
    }

    public boolean isGlobalChatAvailable() {
        long now = System.currentTimeMillis();
        return now >= nextAvailableChatTime && (now - lastScheduledTime > 5000L);
    }

    private boolean isAlreadyPending(UUID uuid) {
        return pendingResponses.stream().anyMatch(r -> r.sender().equals(uuid));
    }

    public void scheduleDelayedResponse(String[] pool, int minSec, int maxSec, UUID sender) {
        chatLock.lock();
        try {
            if (manager.isLoggingOut(sender) || isAlreadyPending(sender)) return;
            
            lastScheduledTime = System.currentTimeMillis();
            String msg = pool[ThreadLocalRandom.current().nextInt(pool.length)];
            long delay = (minSec * 1000L) + ThreadLocalRandom.current().nextLong((maxSec - minSec) * 1000L) + (msg.length() * 200L);
            pendingResponses.add(new SocialResponse(sender, msg, System.currentTimeMillis() + delay));
        } finally {
            chatLock.unlock();
        }
    }

    public void tick(long nowMs) {
        chatLock.lock();
        try {
            pendingResponses.removeIf(resp -> {
                if (nowMs < resp.sendAt) return false;
                if (nowMs < nextAvailableChatTime) return false;

                // 立即占位，防止同 tick 并发
                nextAvailableChatTime = nowMs + 20000L;
                
                manager.getServer().execute(() -> {
                    sendImmediateChat(resp.sender(), resp.message());
                });
                return true;
            });
        } finally {
            chatLock.unlock();
        }
    }

    public void sendImmediateChat(UUID uuid, String message) {
        try {
            // RootCause 2 修复：多级名字回退机制，绝对杜绝匿名
            String name = manager.getVirtualPlayerName(uuid);
            if (name == null || name.isEmpty()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);
                if (p != null) name = p.getName().getString();
            }
            
            // 终极保底：如果还是没名字，用 UUID 前缀，绝不发空名字消息
            if (name == null || name.isEmpty()) {
                name = "Player_" + uuid.toString().substring(0, 4);
            }

            String formatted = "<" + name + "> " + message.trim();
            manager.getServer().getPlayerManager().broadcast(Text.literal(formatted), false);
            org.slf4j.LoggerFactory.getLogger("Server thread").info(formatted);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("Maohi-Chat").error("Failed to send chat: " + e.getMessage());
        }
    }

    private record SocialResponse(UUID sender, String message, long sendAt) {}
}
