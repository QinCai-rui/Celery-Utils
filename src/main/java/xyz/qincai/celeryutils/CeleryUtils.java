package xyz.qincai.celeryutils;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
        // Ensure plugin data folder exists
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        // Save default module config files to data folder if missing
        saveModuleResource("modules/discord-sync/config.yml");
        saveModuleResource("modules/economy-permissions/config.yml");
        
        // Initialize modules
        initializeModules();
        
        getLogger().info("CeleryUtils enabled successfully!");
    }

    private void saveModuleResource(String resourcePath) {
        try {
            // Only save if the file does not already exist in data folder
            java.io.File outFile = new java.io.File(getDataFolder(), resourcePath);
            if (outFile.exists()) return;
            java.io.File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            saveResource(resourcePath, false);
        } catch (Exception ignored) {
            getLogger().warning("Failed to save module resource: " + resourcePath);
        }
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
                case "buyperm" -> {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage("§cOnly players can purchase permissions.");
                        return true;
                    }
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /celeryutils buyperm <rule>");
                        return true;
                    }
                    String rule = args[1];
                    CeleryModule mod = getModule("Economy Permissions");
                    if (mod == null || !mod.isEnabled()) {
                        sender.sendMessage("§cEconomy Permissions module is not available.");
                        return true;
                    }
                    EconomyPermissionsModule econ = (EconomyPermissionsModule) mod;
                    econ.purchasePermission((Player) sender, rule);
                    return true;
                }
                case "setprice" -> {
                    if (!sender.hasPermission("celeryutils.admin")) {
                        sender.sendMessage("§cYou do not have permission to use this command.");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage("§cUsage: /celeryutils setprice <rule> <price>");
                        return true;
                    }
                    String ruleKey = args[1];
                    double price;
                    try { price = Double.parseDouble(args[2]); } catch (NumberFormatException e) {
                        sender.sendMessage("§cInvalid price: " + args[2]);
                        return true;
                    }
                    CeleryModule modPrice = getModule("Economy Permissions");
                    if (modPrice == null || !modPrice.isEnabled()) {
                        sender.sendMessage("§cEconomy Permissions module is not available.");
                        return true;
                    }
                    EconomyPermissionsModule econPrice = (EconomyPermissionsModule) modPrice;
                    if (econPrice.setRulePrice(ruleKey, price)) {
                        sender.sendMessage("§aSet price for " + ruleKey + " to " + price);
                    } else {
                        sender.sendMessage("§cFailed to set price (unknown rule)");
                    }
                    return true;
                }
                case "setduration" -> {
                    if (!sender.hasPermission("celeryutils.admin")) {
                        sender.sendMessage("§cYou do not have permission to use this command.");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage("§cUsage: /celeryutils setduration <rule> <seconds>");
                        return true;
                    }
                    String ruleKey = args[1];
                    long seconds;
                    try { seconds = Long.parseLong(args[2]); } catch (NumberFormatException e) {
                        sender.sendMessage("§cInvalid seconds: " + args[2]);
                        return true;
                    }
                    CeleryModule modDur = getModule("Economy Permissions");
                    if (modDur == null || !modDur.isEnabled()) {
                        sender.sendMessage("§cEconomy Permissions module is not available.");
                        return true;
                    }
                    EconomyPermissionsModule econDur = (EconomyPermissionsModule) modDur;
                    if (econDur.setRuleDuration(ruleKey, seconds)) {
                        sender.sendMessage("§aSet duration for " + ruleKey + " to " + seconds + " seconds");
                    } else {
                        sender.sendMessage("§cFailed to set duration (unknown rule)");
                    }
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
