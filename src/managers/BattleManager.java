package com.minecraft.clanwars.managers;

import com.minecraft.clanwars.ClanWarsPlugin;
import com.minecraft.clanwars.models.*;
import com.minecraft.clanwars.utils.Config;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class BattleManager {
    private final ClanWarsPlugin plugin;
    private final Map<String, Battle> activeBattles; // battle ID -> Battle
    private final Map<String, BattleRequest> battleRequests; // request ID -> BattleRequest
    private final Map<UUID, String> playerBattleMap; // player UUID -> battle ID
    
    public BattleManager(ClanWarsPlugin plugin) {
        this.plugin = plugin;
        this.activeBattles = new ConcurrentHashMap<>();
        this.battleRequests = new ConcurrentHashMap<>();
        this.playerBattleMap = new ConcurrentHashMap<>();
        
        // Load pending battle requests from database
        loadBattleRequests();
    }
    
    private void loadBattleRequests() {
        try {
            Connection conn = plugin.getDatabaseManager().getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "SELECT id, clan1_tag, clan2_tag, requester_uuid, game_mode, scheduled_time, status FROM battle_requests WHERE status = 'PENDING' OR status = 'APPROVED'");
            ResultSet rs = ps.executeQuery();
            
            while (rs.next()) {
                String id = rs.getString("id");
                String clan1Tag = rs.getString("clan1_tag");
                String clan2Tag = rs.getString("clan2_tag");
                UUID requesterUuid = UUID.fromString(rs.getString("requester_uuid"));
                String gameMode = rs.getString("game_mode");
                long scheduledTime = rs.getLong("scheduled_time");
                String status = rs.getString("status");
                
                BattleRequest request = new BattleRequest(id, clan1Tag, clan2Tag, requesterUuid, gameMode, scheduledTime);
                request.setStatus(BattleRequestStatus.valueOf(status));
                
                battleRequests.put(id, request);
            }
            rs.close();
            ps.close();
            
            plugin.getLogger().info("Loaded " + battleRequests.size() + " battle requests from database.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading battle requests from database", e);
        }
    }
    
    public BattleRequest createBattleRequest(String clan1Tag, String clan2Tag, UUID requesterUuid, String gameMode, long scheduledTime) {
        // Validate clans exist
        ClanManager clanManager = plugin.getClanManager();
        if (clanManager.getClan(clan1Tag) == null || clanManager.getClan(clan2Tag) == null) {
            return null;
        }
        
        // Create a unique ID for the request
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        
        // Create the request object
        BattleRequest request = new BattleRequest(requestId, clan1Tag, clan2Tag, requesterUuid, gameMode, scheduledTime);
        
        // Save to database
        try {
            Connection conn = plugin.getDatabaseManager().getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO battle_requests (id, clan1_tag, clan2_tag, requester_uuid, game_mode, scheduled_time, status, request_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, request.getId());
            ps.setString(2, request.getClan1Tag());
            ps.setString(3, request.getClan2Tag());
            ps.setString(4, request.getRequesterUuid().toString());
            ps.setString(5, request.getGameMode());
            ps.setLong(6, request.getScheduledTime());
            ps.setString(7, request.getStatus().toString());
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
            
            // Add to cache
            battleRequests.put(requestId, request);
            
            return request;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error saving battle request to database", e);
            return null;
        }
    }
    
    public boolean approveBattleRequest(String requestId, UUID adminUuid) {
        BattleRequest request = battleRequests.get(requestId);
        if (request == null || request.getStatus() != BattleRequestStatus.PENDING) {
            return false;
        }
        
        // Update status
        request.setStatus(BattleRequestStatus.APPROVED);
        
        // Update in database
        try {
            Connection conn = plugin.getDatabaseManager().getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "UPDATE battle_requests SET status = ?, admin_uuid = ? WHERE id = ?");
            ps.setString(1, request.getStatus().toString());
            ps.setString(2, adminUuid.toString());
            ps.setString(3, request.getId());
            ps.executeUpdate();
            ps.close();
            
            // Schedule the server to start if needed
            plugin.getScheduleManager().scheduleServerStart(request.getScheduledTime(), request.getId());
            
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error updating battle request in database", e);
            return false;
        }
    }
    
    public boolean declineBattleRequest(String requestId, UUID adminUuid) {
        BattleRequest request = battleRequests.get(requestId);
        if (request == null || request.getStatus() != BattleRequestStatus.PENDING) {
            return false;
        }
        
        // Update status
        request.setStatus(BattleRequestStatus.DECLINED);
        
        // Update in database
        try {
            Connection conn = plugin.getDatabaseManager().getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "UPDATE battle_requests SET status = ?, admin_uuid = ? WHERE id = ?");
            ps.setString(1, request.getStatus().toString());
            ps.setString(2, adminUuid.toString());
            ps.setString(3, request.getId());
            ps.executeUpdate();
            ps.close();
            
            // Remove from cache
            battleRequests.remove(requestId);
            
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error updating battle request in database", e);
            return false;
        }
    }
    
    public Battle startBattle(String requestId) {
        BattleRequest request = battleRequests.get(requestId);
        if (request == null || request.getStatus() != BattleRequestStatus.APPROVED) {
            return null;
        }
        
        // Check if staff member is online
        boolean staffOnline = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("clanwars.staff")) {
                staffOnline = true;
                break;
            }
        }
        
        if (!staffOnline && Config.REQUIRE_STAFF_ONLINE) {
            plugin.getLogger().warning("Cannot start battle - no staff member online");
            return null;
        }
        
        // Generate battle ID
        String battleId = "B-" + UUID.randomUUID().toString().substring(0, 6);
        
        // Select an arena
        Arena arena = plugin.getArenaManager().selectArena(request.getGameMode());
        if (arena == null) {
            plugin.getLogger().warning("No suitable arena found for game mode: " + request.getGameMode());
            return null;
        }
        
        // Create the battle object
        Battle battle = new Battle(
            battleId,
            request.getClan1Tag(),
            request.getClan2Tag(),
            request.getGameMode(),
            arena.getId(),
            System.currentTimeMillis()
        );
        
        // Update battle request status
        request.setStatus(BattleRequestStatus.STARTED);
        try {
            Connection conn = plugin.getDatabaseManager().getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "UPDATE battle_requests SET status = ? WHERE id = ?");
            ps.setString(1, request.getStatus().toString());
            ps.setString(2, request.getId());
            ps.executeUpdate();
            ps.close();
            
            // Create battle record in database
            ps = conn.prepareStatement(
                "INSERT INTO battles (id, request_id, clan1_tag, clan2_tag, game_mode, arena_id, start_time, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, battle.getId());
            ps.setString(2, requestId);
            ps.setString(3, battle.getClan1Tag());
            ps.setString(4, battle.getClan2Tag());
            ps.setString(5, battle.getGameMode());
            ps.setString(6, battle.getArenaId());
            ps.setLong(7, battle.getStartTime());
            ps.setString(8, battle.getStatus().toString());
            ps.executeUpdate();
            ps.close();
            
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error updating battle request or creating battle in database", e);
            return null;
        }
        
        // Add to cache
        activeBattles.put(battleId, battle);
        
        // Start the countdown
        startBattleCountdown(battle);
        
        return battle;
    }
    
    private void startBattleCountdown(Battle battle) {
        final int[] countdown = {Config.BATTLE_COUNTDOWN_SECONDS};
        
        // Set up teams and teleport players to waiting area
        setupTeams(battle);
        
        BukkitTask countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (countdown[0] <= 0) {
                // Start the actual battle
                beginBattleFight(battle);
                return;
            }
            
            // Broadcast countdown
            if (countdown[0] <= 10 || countdown[0] % 10 == 0) {
                broadcastToBattle(battle, ChatColor.YELLOW + "Battle starting in " + countdown[0] + " seconds!");
            }
            
            countdown[0]--;
        }, 0L, 20L); // Run every second
        
        // Store the task in the battle object for cancellation if needed
        battle.setCountdownTask(countdownTask);
    }
    
    private void setupTeams(Battle battle) {
        ClanManager clanManager = plugin.getClanManager();
        Clan clan1 = clanManager.getClan(battle.getClan1Tag());
        Clan clan2 = clanManager.getClan(battle.getClan2Tag());
        
        // Get arena and waiting locations
        Arena arena = plugin.getArenaManager().getArena(battle.getArenaId());
        Location team1Waiting = arena.getTeam1SpawnPoint();
        Location team2Waiting = arena.getTeam2SpawnPoint();
        
        // Set up scoreboard teams
        ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        Scoreboard mainScoreboard = scoreboardManager.getMainScoreboard();
        
        // Create or get teams
        Team team1 = mainScoreboard.getTeam("clan1") != null ? 
                     mainScoreboard.getTeam("clan1") : 
                     mainScoreboard.registerNewTeam("clan1");
        Team team2 = mainScoreboard.getTeam("clan2") != null ? 
                     mainScoreboard.getTeam("clan2") : 
                     mainScoreboard.registerNewTeam("clan2");
        
        // Set team colors and prefixes
        team1.setColor(ChatColor.RED);
        team1.setPrefix(ChatColor.RED + "[" + clan1.getTag() + "] ");
        team2.setColor(ChatColor.BLUE);
        team2.setPrefix(ChatColor.BLUE + "[" + clan2.getTag() + "] ");
        
        // Apply friendly fire settings based on game mode
        boolean friendlyFire = battle.getGameMode().equals("FREE_FOR_ALL");
        team1.setAllowFriendlyFire(friendlyFire);
        team2.setAllowFriendlyFire(friendlyFire);
        
        // Get online members and assign to teams
        List<Player> clan1Players = clanManager.getOnlineMembers(clan1);
        List<Player> clan2Players = clanManager.getOnlineMembers(clan2);
        
        // Team balancing if enabled
        if (Config.AUTO_TEAM_BALANCING && clan1Players.size() != clan2Players.size()) {
            balanceTeams(clan1Players, clan2Players);
        }
        
        // Add players to team 1
        for (Player player : clan1Players) {
            team1.addEntry(player.getName());
            playerBattleMap.put(player.getUniqueId(), battle.getId());
            player.teleport(team1Waiting);
            player.setGameMode(GameMode.ADVENTURE);
            player.getInventory().clear();
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.sendMessage(ChatColor.GOLD + "You have been assigned to " + ChatColor.RED + clan1.getName());
            
            // Add to battle participants
            battle.addParticipant(player.getUniqueId(), battle.getClan1Tag());
        }
        
        // Add players to team 2
        for (Player player : clan2Players) {
            team2.addEntry(player.getName());
            playerBattleMap.put(player.getUniqueId(), battle.getId());
            player.teleport(team2Waiting);
            player.setGameMode(GameMode.ADVENTURE);
            player.getInventory().clear();
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.sendMessage(ChatColor.GOLD + "You have been assigned to " + ChatColor.BLUE + clan2.getName());
            
            // Add to battle participants
            battle.addParticipant(player.getUniqueId(), battle.getClan2Tag());
        }
        
        // Set up spectators for other online players
        if (Config.ENABLE_SPECTATOR_MODE) {
            setupSpectators(battle, arena.getSpectatorSpawnPoint());
        }
    }
    
    private void balanceTeams(List<Player> team1, List<Player> team2) {
        // Balance teams by moving players from the larger team to the smaller one
        if (team1.size() > team2.size()) {
            int playersToMove = (team1.size() - team2.size()) / 2;
            for (int i = 0; i < playersToMove && !team1.isEmpty(); i++) {
                Player player = team1.remove(team1.size() - 1);
                team2.add(player);
                player.sendMessage(ChatColor.YELLOW + "You have been moved to the other team for balance!");
            }
        } else if (team2.size() > team1.size()) {
            int playersToMove = (team2.size() - team1.size()) / 2;
            for (int i = 0; i < playersToMove && !team2.isEmpty(); i++) {
                Player player = team2.remove(team2.size() - 1);
                team1.add(player);
                player.sendMessage(ChatColor.YELLOW + "You have been moved to the other team for balance!");
            }
        }
    }
    
    private void setupSpectators(Battle battle, Location spectatorSpawn) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!battle.isParticipant(player.getUniqueId()) && 
                !player.hasPermission("clanwars.staff")) {
                
                player.setGameMode(GameMode.SPECTATOR);
                player.teleport(spectatorSpawn);
                player.sendMessage(ChatColor.GRAY + "You are now spectating the clan battle!");
                
                // Add to battle spectators
                battle.addSpectator(player.getUniqueId());
            }
        }
    }
    
    private void beginBattleFight(Battle battle) {
        // Cancel countdown task
        if (battle.getCountdownTask() != null) {
            battle.getCountdownTask().cancel();
            battle.setCountdownTask(null);
        }
        
        // Set battle as active
        battle.setStatus(BattleStatus.ACTIVE);
        
        // Update in database
        try {
            Connection conn = plugin.getDatabaseManager().getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "UPDATE battles SET status = ? WHERE id = ?");
            ps.setString(1, battle.getStatus().toString());
            ps.setString(2, battle.getId());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error updating battle status in database", e);
        }
        
        // Get arena
        Arena arena = plugin.getArenaManager().getArena(battle.getArenaId());
        
        // Teleport players to their spawn points
        teleportTeamsToSpawns(battle, arena);
        
        // Start battle timer
        startBattleTimer(battle);
        
        // Give equipment based on game mode
        giveEquipment(battle);
        
        // Broadcast battle start
        broadcastToBattle(battle, ChatColor.GREEN + "The battle has begun! Fight!");
        
        // Start scoreboard updates
        startScoreboardUpdates(battle);
    }
    
    private void teleportTeamsToSpawns(Battle battle, Arena arena) {
        ClanManager clanManager = plugin.getClanManager();
        
        // Get team 1 players
        List<Player> team1Players = clanManager.getOnlineMembers(clanManager.getClan(battle.getClan1Tag()))
            .stream()
            .filter(p -> battle.isParticipant(p.getUniqueId()))
            .collect(Collectors.toList());
        
        // Get team 2 players
        List<Player> team2Players = clanManager.getOnlineMembers(clanManager.getClan(battle.getClan2Tag()))
            .stream()
            .filter(p -> battle.isParticipant(p.getUniqueId()))
            .collect(Collectors.toList());
        
        // Teleport team 1
        for (Player player : team1Players) {
            player.teleport(arena.getTeam1SpawnPoint());
            player.setGameMode(GameMode.SURVIVAL);
        }
        
        // Teleport team 2
        for (Player player : team2Players) {
            player.teleport(arena.getTeam2SpawnPoint());
            player.setGameMode(GameMode.SURVIVAL);
        }
    }
    
    private void giveEquipment(Battle battle) {
        // Determine what equipment to give based on game mode
        String gameMode = battle.getGameMode();
        
        for (UUID playerId : battle.getParticipants()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                // Clear inventory first
                player.getInventory().clear();
                
                // Give game mode specific equipment
                switch (gameMode) {
                    case "TEAM_DEATHMATCH":
                        plugin.getItemManager().giveTDMKit(player);
                        break;
                    case "CAPTURE_THE_FLAG":
                        plugin.getItemManager().giveCTFKit(player);
                        break;
                    case "CONTROL_POINT":
                        plugin.getItemManager().giveControlPointKit(player);
                        break;
                    case "FREE_FOR_ALL":
                        plugin.getItemManager().giveFFAKit(player);
                        break;
                    default:
                        plugin.getItemManager().giveDefaultKit(player);
                        break;
                }
            }
        }
    }
    
    private void startBattleTimer(Battle battle) {
        final int[] timeRemaining = {Config.BATTLE_DURATION_MINUTES * 60}; // Convert to seconds
        
        BukkitTask timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (timeRemaining[0] <= 0 || battle.getStatus() != BattleStatus.ACTIVE) {
                // Time's up, end the battle
                endBattle(battle, BattleEndReason.TIME_EXPIRED);
                return;
            }
            
            // Update battle time
            battle.setTimeRemaining(timeRemaining[0]);
            
            // Announce time remaining at specific intervals
            if (timeRemaining[0] <= 60 || // Last minute
                (timeRemaining[0] <= 300 && timeRemaining[0] % 60 == 0) || // Every minute in last 5 minutes
                timeRemaining[0] % 300 == 0) { // Every 5 minutes otherwise
                
                int minutes = timeRemaining[0] / 60;
                int seconds = timeRemaining[0] % 60;
                
                if (minutes > 0) {
                    broadcastToBattle(battle, ChatColor.YELLOW + "Time remaining: " + minutes + " minutes");
                } else {
                    broadcastToBattle(battle, ChatColor.RED + "Time remaining: " + seconds + " seconds!");
                }
            }
            
            timeRemaining[0]--;
        }, 0L, 20L); // Run every second
        
        // Store the task in the battle object for cancellation if needed
        battle.setTimerTask(timerTask);
    }
    
    private void startScoreboardUpdates(Battle battle) {
        BukkitTask scoreboardTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Update scoreboard for all participants and spectators
            for (UUID playerId : battle.getParticipants()) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    updatePlayerScoreboard(player, battle);
                }
            }
            
            for (UUID spectatorId : battle.getSpectators()) {
                Player spectator = Bukkit.getPlayer(spectatorId);
                if (spectator != null && spectator.isOnline()) {
                    updatePlayerScoreboard(spectator, battle);
                }
            }
        }, 0L, 20L); // Update every second
        
        battle.setScoreboardTask(scoreboardTask);
    }
    
    private void updatePlayerScoreboard(Player player, Battle battle) {
        ClanManager clanManager = plugin.getClanManager();
        String clan1Tag = battle.getClan1Tag();
        String clan2Tag = battle.getClan2Tag();
        
        // Here we would set up and update the scoreboard for the player
        // with battle information like time remaining, scores, etc.
        // Implementation depends on the game mode
        
        // For example, for Team Deathmatch:
        plugin.getScoreboardManager().updateBattleScoreboard(
            player,
            battle.getId(),
            clan1Tag,
            clan2Tag,
            battle.getTeam1Score(),
            battle.getTeam2Score(),
            battle.getTimeRemaining(),
            battle.getGameMode()
        );
    }
    
    public void endBattle(Battle battle, BattleEndReason reason) {
        if (battle == null || battle.getStatus() != BattleStatus.ACTIVE) {
            return;
        }
        
        // Cancel all tasks
        if (battle.getTimerTask() != null) {
            battle.getTimerTask().cancel();
        }
        if (battle.getScoreboardTask() != null) {
            battle.getScoreboardTask().cancel();
        }
        
        // Determine winner
        String winnerTag = determineWinner(battle);
        String loserTag = winnerTag.equals(battle.getClan1Tag()) ? battle.getClan2Tag() : battle.getClan1Tag();
        
        // Set battle as ended
        battle.setStatus(BattleStatus.ENDED);
        battle.setEndTime(System.currentTimeMillis());
        battle.setEndReason(reason);
        battle.setWinnerTag(winnerTag);
        
        // Update in database
        saveBattleResults(battle);
        
        // Update clan stats
        plugin.getClanManager().updateClanStats(winnerTag, loserTag);
        
        // Distribute rewards
        distributeRewards(battle);
        
        // Teleport players back to main spawn
        teleportPlayersToMainSpawn(battle);
        
        // Clean up
        cleanupBattle(battle);
        
        // Schedule server shutdown if this was the last battle
        if (activeBattles.size() <= 1 && Config.AUTO_SHUTDOWN_ENABLED) {
            plugin.getServerManager().scheduleShutdown(Config.SHUTDOWN_DELAY_MINUTES);
        }
    }
    
    private String determineWinner(Battle battle) {
        // Logic to determine winner based on game mode and scores
        switch(battle.getGameMode()) {
            case "TEAM_DEATHMATCH":
                // Team with more kills wins
                return battle.getTeam1Score() > battle.getTeam2Score() ? 
                       battle.getClan1Tag() : battle.getClan2Tag();
                
            case "CAPTURE_THE_FLAG":
                // Team with more flag captures wins
                return battle.getTeam1Score() > battle.getTeam2Score() ? 
                       battle.getClan1Tag() : battle.getClan2Tag();
                
            case "CONTROL_POINT":
                // Team with more control point time wins
                return battle.getTeam1Score() > battle.getTeam2Score() ? 
                       battle.getClan1Tag() : battle.getClan2Tag();
                
            default:
                // Default to team with most kills
                // Default to team with most kills
                return battle.getTeam1Score() > battle.getTeam2Score() ? 
                       battle.getClan1Tag() : battle.getClan2Tag();
        }
    }
    
    private void saveBattleResults(Battle battle) {
        try {
            Connection conn = plugin.getDatabaseManager().getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "UPDATE battles SET status = ?, end_time = ?, end_reason = ?, winner_tag = ?, " +
                "team1_score = ?, team2_score = ? WHERE id = ?");
            ps.setString(1, battle.getStatus().toString());
            ps.setLong(2, battle.getEndTime());
            ps.setString(3, battle.getEndReason().toString());
            ps.setString(4, battle.getWinnerTag());
            ps.setInt(5, battle.getTeam1Score());
            ps.setInt(6, battle.getTeam2Score());
            ps.setString(7, battle.getId());
            ps.executeUpdate();
            ps.close();
            
            // Save individual player stats
            savePlayerStats(battle);
            
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error saving battle results to database", e);
        }
    }
    
    private void savePlayerStats(Battle battle) {
        try {
            Connection conn = plugin.getDatabaseManager().getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_battle_stats (battle_id, player_uuid, player_name, clan_tag, " +
                "kills, deaths, score, winner) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            
            for (Map.Entry<UUID, BattlePlayerStats> entry : battle.getPlayerStats().entrySet()) {
                UUID playerId = entry.getKey();
                BattlePlayerStats stats = entry.getValue();
                String playerName = Bukkit.getOfflinePlayer(playerId).getName();
                String clanTag = battle.getPlayerClanTag(playerId);
                boolean isWinner = clanTag.equals(battle.getWinnerTag());
                
                ps.setString(1, battle.getId());
                ps.setString(2, playerId.toString());
                ps.setString(3, playerName);
                ps.setString(4, clanTag);
                ps.setInt(5, stats.getKills());
                ps.setInt(6, stats.getDeaths());
                ps.setInt(7, stats.getScore());
                ps.setBoolean(8, isWinner);
                ps.addBatch();
            }
            
            ps.executeBatch();
            ps.close();
            
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error saving player battle stats to database", e);
        }
    }
    
    private void distributeRewards(Battle battle) {
        String winnerTag = battle.getWinnerTag();
        
        // Get winning clan members
        for (UUID playerId : battle.getParticipants()) {
            String playerClanTag = battle.getPlayerClanTag(playerId);
            Player player = Bukkit.getPlayer(playerId);
            
            if (player != null && player.isOnline()) {
                if (playerClanTag.equals(winnerTag)) {
                    // Reward winning players
                    plugin.getRewardManager().giveWinnerRewards(player, battle);
                    player.sendMessage(ChatColor.GREEN + "Congratulations! Your clan won the battle!");
                } else {
                    // Participation reward for losing players
                    plugin.getRewardManager().giveParticipationRewards(player, battle);
                    player.sendMessage(ChatColor.RED + "Your clan lost the battle. Better luck next time!");
                }
            }
        }
        
        // Broadcast results
        ClanManager clanManager = plugin.getClanManager();
        Clan winnerClan = clanManager.getClan(winnerTag);
        
        Bukkit.broadcastMessage(ChatColor.GOLD + "=== Battle Results ===");
        Bukkit.broadcastMessage(ChatColor.GREEN + "Winner: " + winnerClan.getName() + " [" + winnerClan.getTag() + "]");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "Game Mode: " + battle.getGameMode());
        Bukkit.broadcastMessage(ChatColor.YELLOW + "Score: " + battle.getTeam1Score() + " - " + battle.getTeam2Score());
    }
    
    private void teleportPlayersToMainSpawn(Battle battle) {
        Location mainSpawn = plugin.getServerManager().getMainSpawn();
        
        // Teleport participants
        for (UUID playerId : battle.getParticipants()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.teleport(mainSpawn);
                player.setGameMode(GameMode.SURVIVAL);
                player.getInventory().clear();
                
                // Remove from battle map
                playerBattleMap.remove(playerId);
            }
        }
        
        // Teleport spectators
        for (UUID spectatorId : battle.getSpectators()) {
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null && spectator.isOnline()) {
                spectator.teleport(mainSpawn);
                spectator.setGameMode(GameMode.SURVIVAL);
            }
        }
    }
    
    private void cleanupBattle(Battle battle) {
        // Remove from active battles
        activeBattles.remove(battle.getId());
        
        // Remove scoreboard teams
        ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        Scoreboard mainScoreboard = scoreboardManager.getMainScoreboard();
        
        try {
            if (mainScoreboard.getTeam("clan1") != null) {
                mainScoreboard.getTeam("clan1").unregister();
            }
            if (mainScoreboard.getTeam("clan2") != null) {
                mainScoreboard.getTeam("clan2").unregister();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error removing scoreboard teams", e);
        }
    }
    
    public void endAllBattles() {
        List<Battle> battles = new ArrayList<>(activeBattles.values());
        for (Battle battle : battles) {
            endBattle(battle, BattleEndReason.SERVER_SHUTDOWN);
        }
    }
    
    public Battle getPlayerBattle(UUID playerId) {
        String battleId = playerBattleMap.get(playerId);
        if (battleId == null) {
            return null;
        }
        return activeBattles.get(battleId);
    }
    
    public boolean isPlayerInBattle(UUID playerId) {
        return playerBattleMap.containsKey(playerId);
    }
    
    public void broadcastToBattle(Battle battle, String message) {
        // Send message to all participants and spectators
        for (UUID playerId : battle.getParticipants()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
        
        for (UUID spectatorId : battle.getSpectators()) {
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null && spectator.isOnline()) {
                spectator.sendMessage(message);
            }
        }
    }
    
    public void recordKill(Player killer, Player victim) {
        // Make sure both players are in a battle
        if (!isPlayerInBattle(killer.getUniqueId()) || !isPlayerInBattle(victim.getUniqueId())) {
            return;
        }
        
        // Get the battle
        Battle battle = getPlayerBattle(killer.getUniqueId());
        
        // Make sure they're in the same battle
        if (battle != getPlayerBattle(victim.getUniqueId())) {
            return;
        }
        
        // Get player clans
        String killerClanTag = battle.getPlayerClanTag(killer.getUniqueId());
        String victimClanTag = battle.getPlayerClanTag(victim.getUniqueId());
        
        // Update player stats
        battle.recordKill(killer.getUniqueId(), victim.getUniqueId());
        
        // Update team scores
        if (killerClanTag.equals(battle.getClan1Tag())) {
            battle.setTeam1Score(battle.getTeam1Score() + 1);
        } else if (killerClanTag.equals(battle.getClan2Tag())) {
            battle.setTeam2Score(battle.getTeam2Score() + 1);
        }
        
        // Broadcast kill message
        broadcastToBattle(battle, ChatColor.YELLOW + killer.getName() + " killed " + victim.getName() + "!");
    }
    
    public List<BattleRequest> getPendingBattleRequests() {
        return battleRequests.values().stream()
            .filter(request -> request.getStatus() == BattleRequestStatus.PENDING)
            .collect(Collectors.toList());
    }
    
    public List<BattleRequest> getApprovedBattleRequests() {
        return battleRequests.values().stream()
            .filter(request -> request.getStatus() == BattleRequestStatus.APPROVED)
            .collect(Collectors.toList());
    }
    
    public Map<String, Battle> getActiveBattles() {
        return new HashMap<>(activeBattles);
    }
    
    public BattleRequest getBattleRequest(String requestId) {
        return battleRequests.get(requestId);
    }
    
    public Battle getBattle(String battleId) {
        return activeBattles.get(battleId);
    }
}