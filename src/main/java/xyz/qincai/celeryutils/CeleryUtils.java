package xyz.qincai.celeryutils;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import xyz.qincai.celeryutils.api.CeleryModule;
import xyz.qincai.celeryutils.modules.DiscordLinkModule;
import xyz.qincai.celeryutils.modules.DiscordWhitelistChannelModule;
import xyz.qincai.celeryutils.modules.EconomyPermissionsModule;
import xyz.qincai.celeryutils.modules.DeathPenaltyModule;
import xyz.qincai.celeryutils.updatechecker.UpdateChecker;

import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

/**
 * Main plugin class for CeleryUtils.
 */
public class CeleryUtils extends JavaPlugin implements Listener {

    private static CeleryUtils instance;
    private final Map<String, CeleryModule> modules = new HashMap<>();
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("========================================");
        getLogger().info("CeleryUtils v" + getDescription().getVersion());
        getLogger().info("========================================");

        saveDefaultConfig();
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        upgradeConfig("config.yml", new File(getDataFolder(), "config.yml"), "main config");
        upgradeConfig("modules/discord-link/config.yml", new File(getDataFolder(), "modules/discord-link/config.yml"), "Discord Link module config");
        upgradeConfig("modules/discord-whitelist-channel/config.yml", new File(getDataFolder(), "modules/discord-whitelist-channel/config.yml"), "Discord Whitelist Channel module config");
        upgradeConfig("modules/economy-permissions/config.yml", new File(getDataFolder(), "modules/economy-permissions/config.yml"), "Economy Permissions module config");
        upgradeConfig("modules/death-penalty/config.yml", new File(getDataFolder(), "modules/death-penalty/config.yml"), "Death Penalty module config");

        updateChecker = new UpdateChecker(this);
        updateChecker.start();
        getServer().getPluginManager().registerEvents(this, this);

        initializeModules();

        getLogger().info("CeleryUtils enabled successfully!");
    }

    private void upgradeConfig(String resourcePath, File targetFile, String label) {
        try {
            File legacyTarget = null;
            if ("modules/discord-link/config.yml".equals(resourcePath)) {
                legacyTarget = new File(getDataFolder(), "modules/discord-sync/config.yml");
            }

            if (!targetFile.exists() && legacyTarget != null && legacyTarget.exists()) {
                File parent = targetFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                Files.copy(legacyTarget.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            if (!targetFile.exists()) {
                saveResource(resourcePath, false);
                return;
            }

            InputStream resourceStream = getResource(resourcePath);
            if (resourceStream == null) {
                getLogger().warning("Missing bundled resource for " + resourcePath);
                return;
            }

            FileConfiguration defaultConfig;
            try (InputStream in = resourceStream) {
                defaultConfig = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in));
            }

            FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(targetFile);
            double currentVersion = currentConfig.getDouble("config-version", 0.0);
            double defaultVersion = defaultConfig.getDouble("config-version", currentVersion);

            if (Double.compare(currentVersion, defaultVersion) >= 0) {
                return;
            }

            backupConfig(targetFile, label, currentVersion, defaultVersion);
            mergeMissingValues(currentConfig, defaultConfig, "");
            currentConfig.set("config-version", defaultVersion);
            currentConfig.save(targetFile);
            getLogger().info("Upgraded " + label + " from v" + currentVersion + " to v" + defaultVersion);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to upgrade " + label, e);
        }
    }

    private void backupConfig(File targetFile, String label, double currentVersion, double newVersion) throws IOException {
        File backupDir = new File(getDataFolder(), "backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Unable to create backup directory: " + backupDir);
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String safeLabel = label.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        File backupFile = new File(backupDir, safeLabel + "-v" + currentVersion + "-to-v" + newVersion + "-" + timestamp + ".yml");
        Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void mergeMissingValues(FileConfiguration target, FileConfiguration defaults, String pathPrefix) {
        for (String key : defaults.getKeys(false)) {
            String path = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defaultSection = defaults.getConfigurationSection(key);
                if (defaultSection == null) {
                    continue;
                }

                if (!target.isConfigurationSection(key)) {
                    target.createSection(key);
                }

                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection != null) {
                    mergeMissingValues(targetSection, defaultSection);
                }
                continue;
            }

            if (!target.contains(path)) {
                target.set(path, defaults.get(key));
            }
        }
    }

    private void mergeMissingValues(ConfigurationSection target, ConfigurationSection defaults) {
        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defaultSection = defaults.getConfigurationSection(key);
                if (defaultSection == null) {
                    continue;
                }

                if (!target.isConfigurationSection(key)) {
                    target.createSection(key);
                }

                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection != null) {
                    mergeMissingValues(targetSection, defaultSection);
                }
            } else if (!target.contains(key)) {
                target.set(key, defaults.get(key));
            }
        }
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.stop();
        }
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

    private void initializeModules() {
        if (isModuleEnabled("modules.discord-link.enabled", "modules.discord-sync.enabled")) {
            CeleryModule linkModule = new DiscordLinkModule(this);
            if (linkModule.initialize()) {
                modules.put(linkModule.getName(), linkModule);
                getLogger().info("✓ Loaded module: " + linkModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + linkModule.getName());
            }
        }

        if (getConfig().getBoolean("modules.discord-whitelist-channel.enabled", false)) {
            CeleryModule whitelistModule = new DiscordWhitelistChannelModule(this);
            if (whitelistModule.initialize()) {
                modules.put(whitelistModule.getName(), whitelistModule);
                getLogger().info("✓ Loaded module: " + whitelistModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + whitelistModule.getName());
            }
        }

        if (getConfig().getBoolean("modules.economy-permissions.enabled", true)) {
            CeleryModule economyModule = new EconomyPermissionsModule(this);
            if (economyModule.initialize()) {
                modules.put(economyModule.getName(), economyModule);
                getLogger().info("✓ Loaded module: " + economyModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + economyModule.getName());
            }
        }

        if (getConfig().getBoolean("modules.death-penalty.enabled", true)) {
            CeleryModule deathPenaltyModule = new DeathPenaltyModule(this);
            if (deathPenaltyModule.initialize()) {
                modules.put(deathPenaltyModule.getName(), deathPenaltyModule);
                getLogger().info("✓ Loaded module: " + deathPenaltyModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + deathPenaltyModule.getName());
            }
        }
    }

    public CeleryModule getModule(String name) {
        return modules.get(name);
    }

    public Map<String, CeleryModule> getModules() {
        return new HashMap<>(modules);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (updateChecker != null) {
            updateChecker.notifyIfUpdateAvailable(event.getPlayer());
        }
    }

    public static CeleryUtils getInstance() {
        return instance;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("celeryutils")) {
            return false;
        }

        if (args.length == 0) {
            sendHelp(sender, 1);
            return true;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "status" -> {
                sender.sendMessage("§b§lCeleryUtils §7- §fStatus");
                sender.sendMessage("§fVersion: §a" + getDescription().getVersion());
                sender.sendMessage("§fLoaded Modules: §a" + modules.size());
                for (CeleryModule module : modules.values()) {
                    sender.sendMessage("  §7- §f" + module.getName() + " §7[" + (module.isEnabled() ? "§aENABLED" : "§cDISABLED") + "§7]");
                }
                return true;
            }
            case "link" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can start Discord linking in game.");
                    return true;
                }

                DiscordLinkModule linkModule = getDiscordLinkModule();
                if (linkModule == null || !linkModule.isEnabled()) {
                    player.sendMessage("§cDiscord Link module is not available.");
                    return true;
                }

                linkModule.startLinkSession(player);
                return true;
            }
            case "help" -> {
                if (args.length == 1) {
                    sendHelp(sender, 1);
                    return true;
                }

                String helpArg = args[1].toLowerCase();
                try {
                    sendHelp(sender, Integer.parseInt(helpArg));
                } catch (NumberFormatException ignored) {
                    sendHelpTopic(sender, helpArg);
                }
                return true;
            }
            case "ecoperm" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: §f/cu ecoperm <buy|list> [rule]");
                    return true;
                }

                CeleryModule mod = getModule("Economy Permissions");
                if (mod == null || !mod.isEnabled()) {
                    sender.sendMessage("§cEconomy Permissions module is not available.");
                    return true;
                }

                EconomyPermissionsModule econ = (EconomyPermissionsModule) mod;
                String subAction = args[1].toLowerCase();
                if (subAction.equals("list")) {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("§cOnly players can list permissions.");
                        return true;
                    }

                    econ.listPermissions(player);
                    return true;
                }

                if (subAction.equals("buy")) {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("§cOnly players can purchase permissions.");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage("§cUsage: §f/cu ecoperm buy <rule>");
                        return true;
                    }

                    econ.purchasePermission(player, args[2]);
                    return true;
                }

                sender.sendMessage("§cUnknown action: " + subAction + ". Use buy or list.");
                return true;
            }
            case "update" -> {
                if (!sender.hasPermission("celeryutils.admin")) {
                    sender.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                sender.sendMessage("§b§lCeleryUtils §7- §fUpdate Checker");
                sender.sendMessage("§7Current status: §f" + updateChecker.statusSummary());
                sender.sendMessage("§7Pinging for new updates...");
                updateChecker.runCheckAsync();
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("celeryutils.admin")) {
                    sender.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                sender.sendMessage("§b§lCeleryUtils §7- §fReloading configs and modules...");
                reloadConfigsAndModules(sender);
                return true;
            }
            default -> {
                sender.sendMessage("§cUnknown subcommand. Use /celeryutils help");
                return true;
            }
        }
    }

    private DiscordLinkModule getDiscordLinkModule() {
        CeleryModule module = getModule("Discord Link");
        if (module instanceof DiscordLinkModule linkModule) {
            return linkModule;
        }
        return null;
    }

    private void reloadConfigsAndModules(CommandSender sender) {
        try {
            // Reload main config
            reloadConfig();

            // Ensure module configs are present/merged with defaults
            upgradeConfig("config.yml", new File(getDataFolder(), "config.yml"), "main config");
            upgradeConfig("modules/discord-link/config.yml", new File(getDataFolder(), "modules/discord-link/config.yml"), "Discord Link module config");
            upgradeConfig("modules/discord-whitelist-channel/config.yml", new File(getDataFolder(), "modules/discord-whitelist-channel/config.yml"), "Discord Whitelist Channel module config");
            upgradeConfig("modules/economy-permissions/config.yml", new File(getDataFolder(), "modules/economy-permissions/config.yml"), "Economy Permissions module config");
            upgradeConfig("modules/death-penalty/config.yml", new File(getDataFolder(), "modules/death-penalty/config.yml"), "Death Penalty module config");

            // Determine desired enabled state from config
            boolean wantDiscord = isModuleEnabled("modules.discord-link.enabled", "modules.discord-sync.enabled");
            boolean wantWhitelist = getConfig().getBoolean("modules.discord-whitelist-channel.enabled", false);
            boolean wantEcon = getConfig().getBoolean("modules.economy-permissions.enabled", true);
            boolean wantDeath = getConfig().getBoolean("modules.death-penalty.enabled", true);

            // Discord Link
            CeleryModule discord = getModule("Discord Link");
            if (!wantDiscord) {
                if (discord != null) {
                    try { discord.disable(); } catch (Exception ignored) {}
                    modules.remove("Discord Link");
                    getLogger().info("Disabled module: Discord Link");
                }
            } else {
                if (discord != null) {
                    try { discord.disable(); } catch (Exception ignored) {}
                    modules.remove("Discord Link");
                }
                CeleryModule linkModule = new DiscordLinkModule(this);
                if (linkModule.initialize()) {
                    modules.put(linkModule.getName(), linkModule);
                    getLogger().info("✓ Loaded module: " + linkModule.getName());
                } else {
                    getLogger().warning("✗ Failed to load module: " + linkModule.getName());
                }
            }

            // Discord Whitelist Channel
            CeleryModule whitelist = getModule("Discord Whitelist Channel");
            if (!wantWhitelist) {
                if (whitelist != null) {
                    try { whitelist.disable(); } catch (Exception ignored) {}
                    modules.remove("Discord Whitelist Channel");
                    getLogger().info("Disabled module: Discord Whitelist Channel");
                }
            } else {
                if (whitelist != null) {
                    try { whitelist.disable(); } catch (Exception ignored) {}
                    modules.remove("Discord Whitelist Channel");
                }
                CeleryModule wlModule = new DiscordWhitelistChannelModule(this);
                if (wlModule.initialize()) {
                    modules.put(wlModule.getName(), wlModule);
                    getLogger().info("✓ Loaded module: " + wlModule.getName());
                } else {
                    getLogger().warning("✗ Failed to load module: " + wlModule.getName());
                }
            }

            // Economy Permissions
            CeleryModule econ = getModule("Economy Permissions");
            if (!wantEcon) {
                if (econ != null) {
                    try { econ.disable(); } catch (Exception ignored) {}
                    modules.remove("Economy Permissions");
                    getLogger().info("Disabled module: Economy Permissions");
                }
            } else {
                if (econ != null) {
                    try { econ.disable(); } catch (Exception ignored) {}
                    modules.remove("Economy Permissions");
                }
                CeleryModule econModule = new EconomyPermissionsModule(this);
                if (econModule.initialize()) {
                    modules.put(econModule.getName(), econModule);
                    getLogger().info("✓ Loaded module: " + econModule.getName());
                } else {
                    getLogger().warning("✗ Failed to load module: " + econModule.getName());
                }
            }

            // Death Penalty
            CeleryModule death = getModule("Death Penalty");
            if (!wantDeath) {
                if (death != null) {
                    try { death.disable(); } catch (Exception ignored) {}
                    modules.remove("Death Penalty");
                    getLogger().info("Disabled module: Death Penalty");
                }
            } else {
                if (death != null) {
                    try { death.disable(); } catch (Exception ignored) {}
                    modules.remove("Death Penalty");
                }
                CeleryModule deathModule = new DeathPenaltyModule(this);
                if (deathModule.initialize()) {
                    modules.put(deathModule.getName(), deathModule);
                    getLogger().info("✓ Loaded module: " + deathModule.getName());
                } else {
                    getLogger().warning("✗ Failed to load module: " + deathModule.getName());
                }
            }

            sender.sendMessage("§aReload complete.");
        } catch (Exception e) {
            sender.sendMessage("§cFailed to reload configs/modules: " + e.getMessage());
            getLogger().log(Level.WARNING, "Failed to reload configs/modules", e);
        }
    }

    private boolean isModuleEnabled(String primaryKey, String legacyKey) {
        if (getConfig().contains(primaryKey)) {
            return getConfig().getBoolean(primaryKey, true);
        }
        return getConfig().getBoolean(legacyKey, true);
    }

    private void sendHelp(CommandSender sender, int page) {
        sender.sendMessage("§b§lCeleryUtils §7- §fGlobal Commands");
        sender.sendMessage("§f/cu help §7[topic] - §7Show specific help info");
        sender.sendMessage("§f/cu status §7- §7Check module health and version");
        sender.sendMessage("§f/cu link §7- §7Link Minecraft with Discord");
        sender.sendMessage("§f/cu ecoperm §7- §7Economy Permissions menu");
        if (sender.hasPermission("celeryutils.admin")) {
            sender.sendMessage("§f/cu update §7- §7Check for plugin updates");
            sender.sendMessage("§f/cu reload §7- §7Reload configs and (re)load modules if changed");
        }
        sender.sendMessage("§7For more details use §b/cu help [link|ecoperm|whitelist|admin]§7.");
    }

    private void sendHelpTopic(CommandSender sender, String topic) {
        switch (topic.toLowerCase()) {
            case "link", "discord" -> {
                sender.sendMessage("§b§lCeleryUtils §7- §fDiscord Link Help");
                sender.sendMessage("§f/cu link §7- §7Generates a one-time 6-digit sync code");
                sender.sendMessage("§7Step 1: Get code with §f/cu link");
                sender.sendMessage("§7Step 2: Send code to Discord bot's DM");
                sender.sendMessage("§7Your Discord nickname will sync with your Minecraft name.");
            }
            case "whitelist" -> {
                sender.sendMessage("§b§lCeleryUtils §7- §fDiscord Whitelist Help");
                sender.sendMessage("§7This module automatically whitelists players who send their");
                sender.sendMessage("§7usernames in the configured Discord channel.");
            }
            case "ecoperm", "economy", "perm", "permissions" -> {
                sender.sendMessage("§b§lCeleryUtils §7- §fEconomy Permissions Help");
                sender.sendMessage("§f/cu ecoperm list §7- §7View available permission rules");
                sender.sendMessage("§f/cu ecoperm buy <rule> §7- §7Purchase a permission group");
                if (sender.hasPermission("celeryutils.admin")) {
                    // Config handles pricing and duration
                    sender.sendMessage("§cPrices and durations can be configured in config.yml.");
                }
            }
            case "status", "version" -> {
                sender.sendMessage("§b§lCeleryUtils §7- §fStatus Help");
                sender.sendMessage("§f/cu status §7- §7Shows version, loaded modules, and status.");
            }
            case "admin" -> {
                if (sender.hasPermission("celeryutils.admin")) {
                    sender.sendMessage("§b§lCeleryUtils §7- §fAdmin Help");
                    sender.sendMessage("§f/cu status §7- §7View module loading states");
                    sender.sendMessage("§f/cu update §7- §7Force check for updates");
                    sender.sendMessage("§f/cu reload §7- §7Reload configs and (re)load modules if changed");
                } else {
                    sender.sendMessage("§cYou don't have permission for admin help.");
                }
            }
            default -> sendHelp(sender, 1);
        }
    }
}