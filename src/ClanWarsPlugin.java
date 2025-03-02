package com.minecraft.clanwars;

import com.minecraft.clanwars.commands.*;
import com.minecraft.clanwars.listeners.*;
import com.minecraft.clanwars.managers.*;
import com.minecraft.clanwars.models.*;
import com.minecraft.clanwars.utils.Config;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public class ClanWarsPlugin extends JavaPlugin {
    private static ClanWarsPlugin instance;
    private ClanManager clanManager;
    private BattleManager battleManager;
    private ArenaManager arenaManager;
    private ScheduleManager scheduleManager;
    private Logger logger;
    private FileConfiguration config;
    private ServerManager serverManager;
    private DatabaseManager databaseManager;
    
    @Override
    public void onEnable() {
        // Set instance for access across plugin
        instance = this;
        logger = getLogger();
        
        // Load configuration
        saveDefaultConfig();
        config = getConfig();
        
        // Initialize database connection
        databaseManager = new DatabaseManager(this);
        boolean dbConnected = databaseManager.connect();
        
        if (!dbConnected) {
            logger.severe("Failed to connect to database. Disabling ClanWars plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Initialize managers
        clanManager = new ClanManager(this);
        battleManager = new BattleManager(this);
        arenaManager = new ArenaManager(this);
        serverManager = new ServerManager(this);
        scheduleManager = new ScheduleManager(this);
        
        // Register commands
        registerCommands();
        
        // Register event listeners
        registerEventListeners();
        
        // Start scheduled tasks
        scheduleManager.startScheduleTasks();
        
        // Check if server should be running based on schedule
        if (!serverManager.shouldServerBeRunning()) {
            logger.info("No battles scheduled for current time. Server should be shut down.");
            if (Config.AUTO_SHUTDOWN_ENABLED) {
                logger.info("Auto-shutdown is enabled. Server will shutdown in 30 seconds if no admin overrides.");
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!serverManager.isAdminOverride() && !serverManager.shouldServerBeRunning()) {
                        serverManager.shutdownServer();
                    }
                }, 20 * 30); // 30 seconds delay
            }
        }
        
        logger.info("ClanWars plugin has been enabled!");
    }
    
    @Override
    public void onDisable() {
        // Save data before shutdown
        if (clanManager != null) {
            clanManager.saveAllClans();
        }
        
        if (battleManager != null) {
            battleManager.endAllBattles();
        }
        
        // Close database connection
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        
        logger.info("ClanWars plugin has been disabled!");
    }
    
    private void registerCommands() {
        // Register main command executor
        ClanWarsCommandExecutor mainExecutor = new ClanWarsCommandExecutor(this);
        getCommand("clanwars").setExecutor(mainExecutor);
        getCommand("clan").setExecutor(mainExecutor);
        getCommand("battle").setExecutor(mainExecutor);
        
        // Register admin command executor
        AdminCommandExecutor adminExecutor = new AdminCommandExecutor(this);
        getCommand("cwaradmin").setExecutor(adminExecutor);
    }
    
    private void registerEventListeners() {
        // Player related events
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        
        // Battle related events
        getServer().getPluginManager().registerEvents(new BattleListener(this), this);
        
        // Clan related events
        getServer().getPluginManager().registerEvents(new ClanListener(this), this);
    }
    
    // Getter methods for managers
    public static ClanWarsPlugin getInstance() {
        return instance;
    }
    
    public ClanManager getClanManager() {
        return clanManager;
    }
    
    public BattleManager getBattleManager() {
        return battleManager;
    }
    
    public ArenaManager getArenaManager() {
        return arenaManager;
    }
    
    public ScheduleManager getScheduleManager() {
        return scheduleManager;
    }
    
    public ServerManager getServerManager() {
        return serverManager;
    }
    
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}