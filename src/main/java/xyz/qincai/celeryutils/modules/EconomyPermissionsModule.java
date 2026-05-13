package xyz.qincai.celeryutils.modules;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

/**
 * Economy Permissions Module
 * Grants/revokes permissions based on player economy balance
 */
public class EconomyPermissionsModule implements CeleryModule, Listener {
    
    private final CeleryUtils plugin;
    private Economy economy;
    private Permission permission;
    private boolean enabled = false;
    private final Map<String, EconomyPermissionRule> rules = new HashMap<>();
    private final Set<String> checkedPlayers = Collections.synchronizedSet(new HashSet<>());
    
    public EconomyPermissionsModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public String getName() {
        return "Economy Permissions";
    }
    
    @Override
    public boolean initialize() {
        try {
            // Setup economy
            RegisteredServiceProvider<Economy> economyProvider = 
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            
            if (economyProvider == null) {
                plugin.getLogger().warning("Vault Economy not found! Make sure an economy plugin is installed.");
                return false;
            }
            
            economy = economyProvider.getProvider();
            
            // Setup permissions
            RegisteredServiceProvider<Permission> permissionProvider = 
                    Bukkit.getServicesManager().getRegistration(Permission.class);
            
            if (permissionProvider == null) {
                plugin.getLogger().warning("Vault Permission not found! Make sure a permission plugin is installed.");
                return false;
            }
            
            permission = permissionProvider.getProvider();
            
            // Load permission rules from module config file
            File cfgFile = new File(plugin.getDataFolder(), "modules/economy-permissions/config.yml");
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
            loadPermissionRulesFromConfig(cfg);
            
            // Register listener
            Bukkit.getPluginManager().registerEvents(this, plugin);
            
            enabled = true;
            plugin.getLogger().info("Economy Permissions module initialized!");
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Economy Permissions module", e);
            return false;
        }
    }
    
    @Override
    public void disable() {
        enabled = false;
    }
    
    @Override
    public boolean isEnabled() {
        return enabled && economy != null && permission != null;
    }
    
    /**
     * Loads permission rules from config
     */
    private void loadPermissionRules() {
        // legacy fallback (shouldn't be used now)
        createDefaultRules();
    }

    private void loadPermissionRulesFromConfig(FileConfiguration cfg) {
        rules.clear();
        ConfigurationSection rulesSection = cfg.getConfigurationSection("rules");
        if (rulesSection == null) {
            createDefaultRules();
            return;
        }
        for (String key : rulesSection.getKeys(false)) {
            try {
                double minBalance = rulesSection.getDouble(key + ".min-balance", 0);
                String permissionNode = rulesSection.getString(key + ".permission");
                boolean revoke = rulesSection.getBoolean(key + ".revoke-on-balance-below", false);
                boolean buyable = rulesSection.getBoolean(key + ".buyable", false);
                double price = rulesSection.getDouble(key + ".price", 0.0);
                long durationSeconds = rulesSection.getLong(key + ".duration-seconds", 0L);
                if (permissionNode != null) {
                    rules.put(key, new EconomyPermissionRule(minBalance, permissionNode, revoke, buyable, price, durationSeconds));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load rule: " + key);
            }
        }
        plugin.getLogger().info("Loaded " + rules.size() + " permission rules (module config)");
    }

    /**
     * Attempt to purchase a rule for a player.
     * Returns true if purchase & grant succeeded.
     */
    public boolean purchasePermission(org.bukkit.entity.Player player, String ruleKey) {
        if (!isEnabled()) return false;
        EconomyPermissionRule rule = rules.get(ruleKey);
        if (rule == null) {
            player.sendMessage("§cUnknown permission tier: " + ruleKey);
            return false;
        }
        if (!rule.buyable) {
            player.sendMessage("§cThis permission is not available for purchase.");
            return false;
        }

        double balance = economy.getBalance(player);
        if (balance < rule.price) {
            player.sendMessage("§cYou do not have enough balance. Price: " + rule.price + " Your balance: " + balance);
            return false;
        }

        try {
            var response = economy.withdrawPlayer(player, rule.price);
            if (!response.transactionSuccess()) {
                player.sendMessage("§cFailed to withdraw funds: " + response.errorMessage);
                return false;
            }
            // Grant permission
            permission.playerAdd(player, rule.permissionNode);
            player.sendMessage("§aPurchased and granted permission: " + rule.permissionNode);

            // If temporary, schedule revoke
            if (rule.durationSeconds > 0) {
                long ticks = rule.durationSeconds * 20L;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        permission.playerRemove(player, rule.permissionNode);
                        player.sendMessage("§eYour permission " + rule.permissionNode + " has expired.");
                    } catch (Exception ignored) {}
                }, ticks);
            }
            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing purchase for " + player.getName(), e);
            player.sendMessage("§cAn internal error occurred while purchasing.");
            return false;
        }
    }

    public boolean setRulePrice(String ruleKey, double price) {
        EconomyPermissionRule rule = rules.get(ruleKey);
        if (rule == null) return false;
        rule.price = price;
        // Persist to module config
        File cfgFile = new File(plugin.getDataFolder(), "modules/economy-permissions/config.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
        cfg.set("rules." + ruleKey + ".price", price);
        try { cfg.save(cfgFile); } catch (Exception e) { plugin.getLogger().warning("Failed to save config: " + e.getMessage()); }
        return true;
    }

    public boolean setRuleDuration(String ruleKey, long durationSeconds) {
        EconomyPermissionRule rule = rules.get(ruleKey);
        if (rule == null) return false;
        rule.durationSeconds = durationSeconds;
        File cfgFile = new File(plugin.getDataFolder(), "modules/economy-permissions/config.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
        cfg.set("rules." + ruleKey + ".duration-seconds", durationSeconds);
        try { cfg.save(cfgFile); } catch (Exception e) { plugin.getLogger().warning("Failed to save config: " + e.getMessage()); }
        return true;
    }
    
    /**
     * Creates default permission rules
     */
    private void createDefaultRules() {
        // Example: Grant "vip" permission if balance >= 100
        rules.put("vip-tier", new EconomyPermissionRule(100.0, "celeryutils.vip", true));
        // Example: Grant "premium" permission if balance >= 500
        rules.put("premium-tier", new EconomyPermissionRule(500.0, "celeryutils.premium", true));
    }
    
    /**
     * Checks and updates a player's permissions based on balance
     */
    public void checkAndUpdatePlayerPermissions(Player player) {
        if (!isEnabled() || checkedPlayers.contains(player.getName())) {
            return;
        }
        
        try {
            double balance = economy.getBalance(player);
            
            for (EconomyPermissionRule rule : rules.values()) {
                boolean hasPermission = permission.playerHas(player, rule.permissionNode);
                boolean shouldHavePermission = balance >= rule.minBalance;
                
                if (shouldHavePermission && !hasPermission) {
                    // Grant permission
                    permission.playerAdd(player, rule.permissionNode);
                    plugin.getLogger().info("Granted " + rule.permissionNode + " to " + player.getName());
                    
                } else if (!shouldHavePermission && hasPermission && rule.revokeOnBelowBalance) {
                    // Revoke permission
                    permission.playerRemove(player, rule.permissionNode);
                    plugin.getLogger().info("Revoked " + rule.permissionNode + " from " + player.getName());
                }
            }
            
            checkedPlayers.add(player.getName());
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check permissions for " + player.getName(), e);
        }
    }
    
    /**
     * Resets permission checks for a player (on logout)
     */
    public void resetPlayerCheck(String playerName) {
        checkedPlayers.remove(playerName);
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        resetPlayerCheck(event.getPlayer().getName());
        
        // Schedule permission check on next tick
        Bukkit.getScheduler().runTaskLater(plugin, () -> 
                checkAndUpdatePlayerPermissions(event.getPlayer()), 1L);
    }
    
    /**
     * Gets the current balance of a player
     */
    public double getPlayerBalance(Player player) {
        if (!isEnabled()) {
            return 0;
        }
        return economy.getBalance(player);
    }
    
    /**
     * Permission rule definition
     */
    private static class EconomyPermissionRule {
        final double minBalance;
        final String permissionNode;
        final boolean revokeOnBelowBalance;
        boolean buyable;
        double price;
        long durationSeconds;

        EconomyPermissionRule(double minBalance, String permissionNode, boolean revokeOnBelowBalance) {
            this(minBalance, permissionNode, revokeOnBelowBalance, false, 0.0, 0L);
        }

        EconomyPermissionRule(double minBalance, String permissionNode, boolean revokeOnBelowBalance,
                              boolean buyable, double price, long durationSeconds) {
            this.minBalance = minBalance;
            this.permissionNode = permissionNode;
            this.revokeOnBelowBalance = revokeOnBelowBalance;
            this.buyable = buyable;
            this.price = price;
            this.durationSeconds = durationSeconds;
        }
    }
}
