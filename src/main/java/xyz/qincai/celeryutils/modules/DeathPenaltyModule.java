package xyz.qincai.celeryutils.modules;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.util.logging.Level;

/**
 * Death Penalty Module
 * Penalizes players when they die by deducting experience and/or economy money.
 * Useful for keepInventory survival mode where deaths can be too easy.
 */
public class DeathPenaltyModule implements CeleryModule, Listener {
    
    private final CeleryUtils plugin;
    private Economy economy;
    private boolean enabled = false;
    
    // Configuration values
    private boolean experienceEnabled;
    private boolean economyEnabled;
    private int experienceFlatAmount;
    private double experiencePercentage;
    private boolean experienceUsePercent;
    private double economyMinFloor;
    private double economyMaxCap;
    private double economyPercentage;
    private String bypassPermission;
    
    public DeathPenaltyModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public String getName() {
        return "Death Penalty";
    }
    
    @Override
    public boolean initialize() {
        try {
            // Load configuration
            loadConfig();
            
            // Setup economy (optional - only needed if economy penalty is enabled)
            if (economyEnabled) {
                RegisteredServiceProvider<Economy> economyProvider = 
                        Bukkit.getServicesManager().getRegistration(Economy.class);
                
                if (economyProvider == null) {
                    plugin.getLogger().warning("Death Penalty: Vault Economy not found. Economy penalties will not work.");
                    economyEnabled = false;
                } else {
                    economy = economyProvider.getProvider();
                }
            }
            
            // Register listener
            Bukkit.getPluginManager().registerEvents(this, plugin);
            
            enabled = true;
            plugin.getLogger().info("Death Penalty module initialized!");
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Death Penalty module", e);
            return false;
        }
    }
    
    @Override
    public void disable() {
        enabled = false;
        try {
            HandlerList.unregisterAll(this);
        } catch (Exception ignored) {}

        economy = null;
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Loads configuration from module config file
     */
    private void loadConfig() {
        try {
            File cfgFile = new File(plugin.getDataFolder(), "modules/death-penalty/config.yml");
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
            
            experienceEnabled = cfg.getBoolean("penalties.experience.enabled", true);
            experienceFlatAmount = cfg.getInt("penalties.experience.flat-amount", 1);
            experiencePercentage = cfg.getDouble("penalties.experience.percentage", 10.0);
            experienceUsePercent = cfg.getBoolean("penalties.experience.use-percentage", false);
            
            economyEnabled = cfg.getBoolean("penalties.economy.enabled", true);
            economyPercentage = cfg.getDouble("penalties.economy.percentage", 20.0);
            economyMinFloor = cfg.getDouble("penalties.economy.min-floor", 0.0);
            economyMaxCap = cfg.getDouble("penalties.economy.max-cap", 500.0);
            
            bypassPermission = cfg.getString("bypass-permission", "celeryutils.deathpenalty.bypass");
            
            plugin.getLogger().info("Death Penalty config loaded!");
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load Death Penalty config, using defaults", e);
        }
    }
    
    /**
     * Handles player death event
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!enabled) return;
        
        Player player = event.getEntity();
        
        // Check if player has bypass permission
        if (player.hasPermission(bypassPermission)) {
            return;
        }
        
        // Check if world has keepInventory enabled
        if (!player.getWorld().getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY)) {
            return;
        }
        
        // Apply experience penalty
        if (experienceEnabled) {
            applyExperiencePenalty(player);
        }
        
        // Apply economy penalty
        if (economyEnabled && economy != null) {
            applyEconomyPenalty(player);
        }
    }
    
    /**
     * Applies experience penalty to player
     */
    private void applyExperiencePenalty(Player player) {
        int currentLevel = player.getLevel();
        int penaltyAmount;
        
        if (experienceUsePercent) {
            // Deduct a percentage of current levels
            penaltyAmount = (int) Math.max(1, Math.floor(currentLevel * experiencePercentage / 100.0));
        } else {
            // Deduct flat amount
            penaltyAmount = experienceFlatAmount;
        }
        
        // Ensure we don't go below level 0
        int newLevel = Math.max(0, currentLevel - penaltyAmount);
        player.setLevel(newLevel);
        
        int actualPenalty = currentLevel - newLevel;
        player.sendMessage("§c§lDeath Penalty§r§f You lost §c" + actualPenalty + "§f level" + (actualPenalty != 1 ? "s" : "") + ".");
    }
    
    /**
     * Applies economy penalty to player
     */
    private void applyEconomyPenalty(Player player) {
        try {
            double balance = economy.getBalance(player);
            
            // Calculate penalty as percentage of balance
            double penaltyAmount = balance * economyPercentage / 100.0;
            
            // Apply floor (minimum)
            if (penaltyAmount < economyMinFloor) {
                penaltyAmount = Math.min(economyMinFloor, balance);
            }
            
            // Apply ceiling only when a positive cap is configured
            if (economyMaxCap > 0 && penaltyAmount > economyMaxCap) {
                penaltyAmount = economyMaxCap;
            }
            
            // Ensure we have enough balance (don't go negative)
            penaltyAmount = Math.min(penaltyAmount, balance);
            
            if (penaltyAmount > 0) {
                var response = economy.withdrawPlayer(player, penaltyAmount);
                if (response.transactionSuccess()) {
                    player.sendMessage("§c§lDeath Penalty§r§f You lost §c$" + String.format("%.2f", penaltyAmount) + "§f from your balance.");
                } else {
                    plugin.getLogger().warning("Failed to apply economy penalty to " + player.getName() + ": " + response.errorMessage);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error applying economy penalty to " + player.getName(), e);
        }
    }
}
