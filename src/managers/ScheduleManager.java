package com.minecraft.clanwars.managers;

import com.minecraft.clanwars.ClanWarsPlugin;
import com.minecraft.clanwars.models.Battle;
import com.minecraft.clanwars.models.BattleRequest;
import com.minecraft.clanwars.models.BattleRequestStatus;
import com.minecraft.clanwars.utils.Config;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ScheduleManager {
    private final ClanWarsPlugin plugin;
    private BukkitTask scheduleCheckTask;
    private final Map<String, BukkitTask> scheduledStarts; // request ID -> scheduled task
    
    public ScheduleManager(ClanWarsPlugin plugin) {
        this.plugin = plugin;
        this.scheduledStarts = new ConcurrentHashMap<>();
    }
    
    public void startScheduleTasks() {
        // Schedule regular checks for pending battles
        scheduleCheckTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkScheduledBattles, 20L, 20L * 60); // Check every minute
        
        // Schedule already approved battles
        scheduleApprovedBattles();
    }
    
    private void scheduleApprovedBattles() {
        BattleManager battleManager = plugin.getBattleManager();
        List<BattleRequest> approvedRequests = battleManager.getApprovedBattleRequests();
        
        for (BattleRequest request : approvedRequests) {
            scheduleServerStart(request.getScheduledTime(), request.getId());
        }
    }
    
    public void scheduleServerStart(long scheduledTime, String requestId) {
        // Cancel any existing task for this request
        BukkitTask existingTask = scheduledStarts.get(requestId);
        if (existingTask != null) {
            existingTask.cancel();
        }
        
        // Calculate delay in ticks (20 ticks = 1 second)
        long currentTime = System.currentTimeMillis();
        long delay = scheduledTime - currentTime;
        
        if (delay <= 0) {
            // Battle should start now
            plugin.getLogger().info("Battle " + requestId + " should start now. Starting server...");
            startBattleServer(requestId);
            return;
        }
        
        // Convert to ticks
        long ticksDelay = delay / 50; // 50ms per tick
        
        plugin.getLogger().info("Scheduling battle " + requestId + " to start in " + (delay / 1000) + " seconds");
        
        // Schedule the task
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            startBattleServer(requestId);
        }, ticksDelay);
        
        // Store the task
        scheduledStarts.put(requestId, task);
    }
    
    private void startBattleServer(String requestId) {
        // Remove from scheduled starts
        scheduledStarts.remove(requestId);
        
        // Check if server is already running
        if (plugin.getServerManager().isServerRunning()) {
            plugin.getLogger().info("Server is already running. Starting battle " + requestId);
            startBattle(requestId);
            return;
        }
        
        // Start the server
        boolean success = plugin.getServerManager().startServer();
        
        if (success) {
            plugin.getLogger().info("Server started successfully for battle " + requestId);
            
            // Schedule the battle to start after server is fully up
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                startBattle(requestId);
            }, 20 * 30); // 30 seconds delay to ensure server is ready
        } else {
            plugin.getLogger().severe("Failed to start server for battle " + requestId);
        }
    }
    
    private void startBattle(String requestId) {
        BattleManager battleManager = plugin.getBattleManager();
        BattleRequest request = battleManager.getBattleRequest(requestId);
        
        if (request == null || request.getStatus() != BattleRequestStatus.APPROVED) {
            plugin.getLogger().warning("Cannot start battle " + requestId + ": request not found or not approved");
            return;
        }
        
        // Start the battle
        Battle battle = battleManager.startBattle(requestId);
        
        if (battle == null) {
            plugin.getLogger().severe("Failed to start battle " + requestId);
        } else {
            plugin.getLogger().info("Battle " + battle.getId() + " started successfully");
        }
    }
    
    private void checkScheduledBattles() {
        try {
            long currentTime = System.currentTimeMillis();
            BattleManager battleManager = plugin.getBattleManager();
            List<BattleRequest> pendingRequests = battleManager.getPendingBattleRequests();
            
            for (BattleRequest request : pendingRequests) {
                long scheduledTime = request.getScheduledTime();
                long timeUntilBattle = scheduledTime - currentTime;
                
                // Notify admins of upcoming battles that need approval
                if (timeUntilBattle > 0 && timeUntilBattle < 24 * 60 * 60 * 1000) { // Less than 24 hours away
                    notifyAdminsOfPendingBattle(request);
                }
                
                // Auto-decline battles that are past their scheduled time
                if (timeUntilBattle < 0 && Math.abs(timeUntilBattle) > Config.BATTLE_AUTO_DECLINE_DELAY) {
                    autoDeclineBattle(request);
                }
            }
            
            // Check for upcoming approved battles that need server preparation
            List<BattleRequest> approvedRequests = battleManager.getApprovedBattleRequests();
            for (BattleRequest request : approvedRequests) {
                long timeUntilBattle = request.getScheduledTime() - currentTime;
                
                // Prepare server if battle is approaching
                if (timeUntilBattle > 0 && timeUntilBattle < Config.SERVER_PREP_TIME) {
                    ensureServerPreparation(request);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error checking scheduled battles", e);
        }
    }
    
    private void notifyAdminsOfPendingBattle(BattleRequest request) {
        String message = String.format(
            "§c[ClanWars] §ePending battle request needs approval: %s vs %s (Scheduled for: %s)",
            request.getClan1Tag(),
            request.getClan2Tag(),
            formatTime(request.getScheduledTime())
        );
        
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.hasPermission("clanwars.admin")) {
                player.sendMessage(message);
            }
        });
    }
    
    private void autoDeclineBattle(BattleRequest request) {
        plugin.getLogger().info("Auto-declining expired battle request: " + request.getId());
        plugin.getBattleManager().declineBattleRequest(
            request.getId(),
            UUID.fromString(Config.SYSTEM_UUID)
        );
    }
    
    private void ensureServerPreparation(BattleRequest request) {
        if (!scheduledStarts.containsKey(request.getId())) {
            plugin.getLogger().info("Preparing server for upcoming battle: " + request.getId());
            scheduleServerStart(request.getScheduledTime(), request.getId());
        }
    }
    
    private String formatTime(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        return String.format(
            "%02d/%02d/%d %02d:%02d",
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.YEAR),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE)
        );
    }
    
    public void cancelScheduledStart(String requestId) {
        BukkitTask task = scheduledStarts.remove(requestId);
        if (task != null) {
            task.cancel();
            plugin.getLogger().info("Cancelled scheduled start for battle: " + requestId);
        }
    }
    
    public void shutdown() {
        if (scheduleCheckTask != null) {
            scheduleCheckTask.cancel();
        }
        
        // Cancel all scheduled starts
        scheduledStarts.values().forEach(BukkitTask::cancel);
        scheduledStarts.clear();
    }
    
    public boolean hasScheduledStart(String requestId) {
        return scheduledStarts.containsKey(requestId);
    }
    
    public Map<String, BukkitTask> getScheduledStarts() {
        return new ConcurrentHashMap<>(scheduledStarts);
    }
}