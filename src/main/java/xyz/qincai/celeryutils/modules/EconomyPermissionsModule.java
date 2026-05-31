package xyz.qincai.celeryutils.modules;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
    private final Map<UUID, Set<String>> dbPurchasedPerms = new java.util.concurrent.ConcurrentHashMap<>();
    private final NamespacedKey purchasedKey;
    private final Map<Integer, BukkitTask> revokeTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private BukkitTask periodicCheckTask;
    
    public EconomyPermissionsModule(CeleryUtils plugin) {
        this.plugin = plugin;
        this.purchasedKey = new NamespacedKey(plugin, "purchased_permissions");
    }
    
    @Override
    public String getName() {
        return "Economy Permissions";
    }
    
    @Override
    public boolean initialize() {
        try {
            plugin.getDatabaseManager().executeUpdate("CREATE TABLE IF NOT EXISTS economy_permissions (minecraft_uuid VARCHAR(36), permission_node VARCHAR(255), PRIMARY KEY (minecraft_uuid, permission_node))");
            loadFromDatabase();

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
            runPeriodicCheck(); // Start periodic balance checking
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
        try {
            HandlerList.unregisterAll(this);
        } catch (Exception ignored) {}

        // Cancel any scheduled revoke tasks
        try {
            if (periodicCheckTask != null) {
                periodicCheckTask.cancel();
                periodicCheckTask = null;
            }
            for (BukkitTask task : revokeTasks.values()) {
                if (task != null) task.cancel();
            }
            revokeTasks.clear();
        } catch (Exception ignored) {}

        // Clear internal state
        rules.clear();
        economy = null;
        permission = null;
    }
    
    @Override
    public boolean isEnabled() {
        return enabled && economy != null && permission != null;
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
                boolean autoGrant = rulesSection.getBoolean(key + ".auto-grant", true);
                boolean buyable = rulesSection.getBoolean(key + ".buyable", false);
                double price = rulesSection.getDouble(key + ".price", 0.0);
                long durationSeconds = rulesSection.getLong(key + ".duration-seconds", 0L);
                String description = rulesSection.getString(key + ".description", "No description provided.");
                String server = rulesSection.getString(key + ".server");
                String world = rulesSection.getString(key + ".world");
                if (permissionNode != null) {
                    rules.put(key, new EconomyPermissionRule(minBalance, permissionNode, revoke, autoGrant, buyable, price, durationSeconds, description, server, world));
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
        if (!rule.buyable()) {
            player.sendMessage("§cThis permission is not available for purchase.");
            return false;
        }

        double balance = economy.getBalance(player);
        if (balance < rule.price()) {
            player.sendMessage("§cYou do not have enough balance. Price: " + rule.price() + " Your balance: " + balance);
            return false;
        }

        try {
            var response = economy.withdrawPlayer(player, rule.price());
            if (!response.transactionSuccess()) {
                player.sendMessage("§cFailed to withdraw funds: " + response.errorMessage);
                return false;
            }
            // Grant permission
            permission.playerAdd(rule.world(), player, rule.permissionNode());            
            // Mark as purchased to prevent balance-based revocation
            addToDatabase(player.getUniqueId(), rule.permissionNode());
            
            player.sendMessage("§aPurchased and granted permission: " + rule.permissionNode());

            // If temporary, schedule revoke
            if (rule.durationSeconds() > 0) {
                long ticks = rule.durationSeconds() * 20L;
                BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            permission.playerRemove(rule.world(), player, rule.permissionNode());
                            player.sendMessage("§eYour permission " + rule.permissionNode() + " has expired.");
                        } catch (Exception ignored) {}
                        revokeTasks.remove(this.getTaskId());
                    }
                }.runTaskLater(plugin, ticks);
                revokeTasks.put(task.getTaskId(), task);
            }
            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing purchase for " + player.getName(), e);
            player.sendMessage("§cAn internal error occurred while purchasing.");
            return false;
        }
    }

    /**
     * Checks player balance against rules on join
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();

        // Migrate from PDC
        org.bukkit.persistence.PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.has(purchasedKey, org.bukkit.persistence.PersistentDataType.STRING)) {
            String purchased = data.get(purchasedKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (purchased != null && !purchased.isEmpty()) {
                plugin.getLogger().info("Migrating economy purchases to DB for " + player.getName());
                for (String perm : purchased.split(",")) {
                    if (!perm.isEmpty()) addToDatabase(player.getUniqueId(), perm);
                }
            }
            data.remove(purchasedKey);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            checkPlayerBalance(player);
        });
    }

    /**
     * Periodically check all online players
     */
    public void runPeriodicCheck() {
        if (!isEnabled()) return;
        if (periodicCheckTask != null) periodicCheckTask.cancel();

        periodicCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                checkPlayerBalance(player);
            }
        }, 20L * 60 * 5, 20L * 60 * 5); // Every 5 minutes
    }

    /**
     * Checks a player's balance and applies/revokes permissions
     */
    private void checkPlayerBalance(Player player) {
        if (!player.isOnline()) return;
        double balance = economy.getBalance(player);
        
        // Get list of permissions this player has purchased
        Set<String> purchasedList = dbPurchasedPerms.getOrDefault(player.getUniqueId(), Collections.emptySet());

        for (Map.Entry<String, EconomyPermissionRule> entry : rules.entrySet()) {
            EconomyPermissionRule rule = entry.getValue();
            boolean hasPerm = permission.playerHas(rule.world(), player, rule.permissionNode());
            boolean isPurchased = purchasedList.contains(rule.permissionNode());
            
            if (balance >= rule.minBalance()) {
                if (!hasPerm && rule.autoGrant()) {
                    permission.playerAdd(rule.world(), player, rule.permissionNode());
                }
            } else {
                // Only revoke if they haven't explicitly purchased it
                if (hasPerm && rule.revokeOnBalanceBelow() && !isPurchased) {
                    permission.playerRemove(rule.world(), player, rule.permissionNode());
                }
            }
        }
    }

    /**
     * Creates default rules if config is missing
     */
    /**
     * Creates default rules if config is missing
     */
    private void createDefaultRules() {
        plugin.getLogger().info("Creating default economy permission rules...");
        rules.put("vip", new EconomyPermissionRule(1000.0, "group.vip", true, true, false, 0.0, 0L, "VIP status based on balance", null, null));
        rules.put("premium", new EconomyPermissionRule(5000.0, "group.premium", true, true, false, 0.0, 0L, "Premium status based on balance", null, null));
    }

    /**
     * Lists all available permission tiers to a player
     */
    public void listPermissions(Player player) {
        player.sendMessage("§b§lCeleryUtils §7- §fAvailable Permission Tiers");
        for (Map.Entry<String, EconomyPermissionRule> entry : rules.entrySet()) {
            String key = entry.getKey();
            EconomyPermissionRule rule = entry.getValue();
            player.sendMessage("  §7- §b" + key + " §7(" + rule.permissionNode() + ")");
            player.sendMessage("    §fDescription: §7" + rule.description());
            if (rule.buyable()) {
                player.sendMessage("    §fPrice: §a$" + rule.price());
            }
            if (rule.minBalance() > 0) {
                player.sendMessage("    §fRequirement: §e$" + rule.minBalance() + " balance");
            }
        }
    }

    private void loadFromDatabase() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT minecraft_uuid, permission_node FROM economy_permissions")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("minecraft_uuid"));
                dbPurchasedPerms.computeIfAbsent(uuid, k -> new HashSet<>())
                                .add(rs.getString("permission_node"));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load economy permissions from database", e);
        }
    }

    private void addToDatabase(UUID uuid, String permNode) {
        dbPurchasedPerms.computeIfAbsent(uuid, k -> new HashSet<>()).add(permNode);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (plugin.getDatabaseManager().getType() == xyz.qincai.celeryutils.database.DatabaseType.SQLITE) {
                    plugin.getDatabaseManager().executeUpdate("INSERT OR IGNORE INTO economy_permissions (minecraft_uuid, permission_node) VALUES ('" + uuid.toString() + "', '" + permNode + "')");
                } else {
                    plugin.getDatabaseManager().executeUpdate("INSERT IGNORE INTO economy_permissions (minecraft_uuid, permission_node) VALUES ('" + uuid.toString() + "', '" + permNode + "')");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert economy permission into db", e);
            }
        });
    }

    private void removeFromDatabase(UUID uuid, String permNode) {
        Set<String> perms = dbPurchasedPerms.get(uuid);
        if (perms != null) perms.remove(permNode);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getDatabaseManager().executeUpdate("DELETE FROM economy_permissions WHERE minecraft_uuid='" + uuid.toString() + "' AND permission_node='" + permNode + "'");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete economy permission from db", e);
            }
        });
    }
}
