package xyz.qincai.celeryutils;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import xyz.qincai.celeryutils.api.CeleryModule;
import xyz.qincai.celeryutils.modules.DiscordMinecraftSyncModule;
import xyz.qincai.celeryutils.modules.EconomyPermissionsModule;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Main plugin class for CeleryUtils
 * Manages module loading and coordination
 */
public class CeleryUtils extends JavaPlugin {
    
    private static CeleryUtils instance;
    private final Map<String, CeleryModule> modules = new HashMap<>();
    
    @Override
    public void onEnable() {
        instance = this;
        
        getLogger().info("========================================");
        getLogger().info("CeleryUtils v" + getDescription().getVersion());
        getLogger().info("========================================");
        
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Initialize modules
        initializeModules();
        
        getLogger().info("CeleryUtils enabled successfully!");
    }
    
    @Override
    public void onDisable() {
        // Disable all modules
        for (CeleryModule module : modules.values()) {
            try {
                module.disable();
                getLogger().info("Disabled module: " + module.getName());
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error disabling module: " + module.getName(), e);
            }
        }
        
        getLogger().info("CeleryUtils disabled!");
    }
    
    /**
     * Initializes all modules based on configuration
     */
    private void initializeModules() {
        // Discord-Minecraft Sync Module
        if (getConfig().getBoolean("modules.discord-sync.enabled", true)) {
            CeleryModule syncModule = new DiscordMinecraftSyncModule(this);
            if (syncModule.initialize()) {
                modules.put(syncModule.getName(), syncModule);
                getLogger().info("✓ Loaded module: " + syncModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + syncModule.getName());
            }
        }
        
        // Economy Permissions Module
        if (getConfig().getBoolean("modules.economy-permissions.enabled", true)) {
            CeleryModule economyModule = new EconomyPermissionsModule(this);
            if (economyModule.initialize()) {
                modules.put(economyModule.getName(), economyModule);
                getLogger().info("✓ Loaded module: " + economyModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + economyModule.getName());
            }
        }
    }
    
    /**
     * Gets a module by name
     */
    public CeleryModule getModule(String name) {
        return modules.get(name);
    }
    
    /**
     * Gets all loaded modules
     */
    public Map<String, CeleryModule> getModules() {
        return new HashMap<>(modules);
    }
    
    /**
     * Gets the plugin instance
     */
    public static CeleryUtils getInstance() {
        return instance;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("celeryutils")) {
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }
            
            String subcommand = args[0].toLowerCase();
            
            switch (subcommand) {
                case "status" -> {
                    sender.sendMessage("§6=== CeleryUtils Status ===");
                    sender.sendMessage("§fVersion: §a" + getDescription().getVersion());
                    sender.sendMessage("§fLoaded Modules: §a" + modules.size());
                    for (CeleryModule module : modules.values()) {
                        sender.sendMessage("  § - " + module.getName() + " §f[" + (module.isEnabled() ? "§aENABLED" : "§cDISABLED") + "§f]");
                    }
                    return true;
                }
                case "help" -> {
                    sendHelp(sender);
                    return true;
                }
                default -> {
                    sender.sendMessage("§cUnknown subcommand. Use /celeryutils help");
                    return true;
                }
            }
        }
        return false;
    }
    
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== CeleryUtils Commands ===");
        sender.sendMessage("§f/celeryutils status - §7Show module status");
        sender.sendMessage("§f/celeryutils help - §7Show this help message");
    }
}
