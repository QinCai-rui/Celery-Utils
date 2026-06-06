package xyz.qincai.celeryutils;

import org.bukkit.Bukkit;
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
import xyz.qincai.celeryutils.modules.PvPModule;
import xyz.qincai.celeryutils.modules.TotemEnhancementsModule;
import xyz.qincai.celeryutils.modules.EssentialsModule;
import xyz.qincai.celeryutils.updatechecker.UpdateChecker;
import xyz.qincai.celeryutils.database.DatabaseManager;
import xyz.qincai.celeryutils.logging.NamespaceLogCleaner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private DatabaseManager databaseManager;
    private NamespaceLogCleaner namespaceLogCleaner;

    @Override
    public void onEnable() {
        instance = this;
        namespaceLogCleaner = new NamespaceLogCleaner();
        namespaceLogCleaner.install();

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
        upgradeConfig("modules/pvp-module/config.yml", new File(getDataFolder(), "modules/pvp-module/config.yml"), "PvP module config");
        upgradeConfig("modules/totemenhancements/config.yml", new File(getDataFolder(), "modules/totemenhancements/config.yml"), "TotemEnhancements module config");
        upgradeConfig("modules/essentials/config.yml", new File(getDataFolder(), "modules/essentials/config.yml"), "Utility Tools module config");
        saveResourceIfAbsent("modules/essentials/motds.yml");
        saveResourceIfAbsent("modules/essentials/tips.yml");

        databaseManager = new DatabaseManager(this);
        databaseManager.initialize(getConfig().getConfigurationSection("database"));

        updateChecker = new UpdateChecker(this);
        updateChecker.start();
        getServer().getPluginManager().registerEvents(this, this);

        initializeModules();

        getLogger().info("CeleryUtils enabled successfully!");
    }

    private void upgradeConfig(String resourcePath, File targetFile, String label) {
        try {
            File legacyTarget = null;
            if ("modules/totemenhancements/config.yml".equals(resourcePath)) {
                File legacyNew = new File(getDataFolder(), "modules/inventory-totems/config.yml");
                File legacyOld = new File(getDataFolder(), "modules/inventory-totem/config.yml");
                if (legacyNew.exists()) {
                    legacyTarget = legacyNew;
                } else if (legacyOld.exists()) {
                    legacyTarget = legacyOld;
                }
            } else if ("modules/discord-link/config.yml".equals(resourcePath)) {
                legacyTarget = new File(getDataFolder(), "modules/discord-sync/config.yml");
            } else if ("modules/essentials/config.yml".equals(resourcePath)) {
                legacyTarget = new File(getDataFolder(), "modules/afk/config.yml");
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
            saveYamlWithComments(targetFile, resourcePath, currentConfig);
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

    private void saveResourceIfAbsent(String resourcePath) {
        File target = new File(getDataFolder(), resourcePath);
        if (!target.exists()) {
            saveResource(resourcePath, false);
        }
    }

    private void saveYamlWithComments(File targetFile, String resourcePath, FileConfiguration mergedConfig) throws IOException {
        List<String> templateLines;
        try (InputStream in = getResource(resourcePath);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            templateLines = r.lines().toList();
        }

        List<String> output = new ArrayList<>();
        Deque<String> pathStack = new ArrayDeque<>();
        Set<String> seenPaths = new HashSet<>();
        String skipListPath = null;

        for (String line : templateLines) {
            if (skipListPath != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- ") || trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                int depth = indent / 2;
                int listDepth = skipListPath.split("\\.").length;
                if (depth <= listDepth) {
                    skipListPath = null;
                } else {
                    continue;
                }
            }

            if (line.isBlank() || line.trim().startsWith("#")) {
                output.add(line);
                continue;
            }

            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            int depth = indent / 2;

            while (pathStack.size() > depth) {
                pathStack.removeLast();
            }

            String trimmed = line.trim();
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx == -1) {
                output.add(line);
                continue;
            }

            String key = trimmed.substring(0, colonIdx);
            pathStack.addLast(key);
            String fullPath = String.join(".", pathStack);

            String valueStr = trimmed.substring(colonIdx + 1).trim();

            if (valueStr.isEmpty() && mergedConfig.contains(fullPath) && mergedConfig.isList(fullPath)) {
                output.add(repeat(" ", indent) + key + ":");
                for (Object item : mergedConfig.getList(fullPath)) {
                    output.add(repeat(" ", indent + 2) + "- " + yamlScalar(item));
                }
                seenPaths.add(fullPath);
                skipListPath = fullPath;
                continue;
            }

            if (!valueStr.isEmpty() && mergedConfig.contains(fullPath) && !mergedConfig.isConfigurationSection(fullPath)) {
                output.add(repeat(" ", indent) + key + ": " + yamlScalar(mergedConfig.get(fullPath)));
            } else {
                output.add(line);
            }
            seenPaths.add(fullPath);
        }

        for (String fullPath : mergedConfig.getKeys(true)) {
            if (seenPaths.contains(fullPath) || mergedConfig.isConfigurationSection(fullPath)) {
                continue;
            }
            int lastDot = fullPath.lastIndexOf('.');
            String parentPath = lastDot == -1 ? "" : fullPath.substring(0, lastDot);
            String shortKey = lastDot == -1 ? fullPath : fullPath.substring(lastDot + 1);
            int depth = parentPath.isEmpty() ? 0 : parentPath.split("\\.").length;

            if (!parentPath.isEmpty() && !seenPaths.contains(parentPath)) {
                StringBuilder pp = new StringBuilder();
                for (String seg : parentPath.split("\\.")) {
                    String pFull = pp.isEmpty() ? seg : pp + "." + seg;
                    if (!seenPaths.contains(pFull)) {
                        output.add(repeat(" ", pp.length() == 0 ? 0 : (pp.toString().split("\\.").length * 2)) + seg + ":");
                        seenPaths.add(pFull);
                    }
                    pp = pp.isEmpty() ? new StringBuilder(seg) : pp.append(".").append(seg);
                }
            }

            output.add(repeat(" ", depth * 2) + shortKey + ": " + yamlScalar(mergedConfig.get(fullPath)));
        }

        Files.writeString(targetFile.toPath(), String.join("\n", output) + "\n", StandardCharsets.UTF_8);
    }

    private String yamlScalar(Object value) {
        if (value == null) return "";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        String s = value.toString();
        if (s.isEmpty()) return "''";
        if (s.matches(".*[#:\\[\\]{},&*?|\\-<>=!%@`].*") || s.matches("^[\\s'\"].*") || s.matches(".*[\\s'\"]$")) {
            return "'" + s.replace("'", "''") + "'";
        }
        return s;
    }

    private String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    @Override
    public void onDisable() {
        if (namespaceLogCleaner != null) {
            namespaceLogCleaner.uninstall();
            namespaceLogCleaner = null;
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
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

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
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

        if (getConfig().getBoolean("modules.pvp-module.enabled", true)) {
            CeleryModule pvpModule = new PvPModule(this);
            if (pvpModule.initialize()) {
                modules.put(pvpModule.getName(), pvpModule);
                getLogger().info("✓ Loaded module: " + pvpModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + pvpModule.getName());
            }
        }

        if (getConfig().getBoolean("modules.totemenhancements.enabled", true)) {
            CeleryModule totemModule = new TotemEnhancementsModule(this);
            if (totemModule.initialize()) {
                modules.put(totemModule.getName(), totemModule);
                getLogger().info("✓ Loaded module: " + totemModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + totemModule.getName());
            }
        }

        if (isModuleEnabled("modules.essentials.enabled", "modules.afk.enabled")) {
            CeleryModule utilityToolsModule = new EssentialsModule(this);
            if (utilityToolsModule.initialize()) {
                modules.put(utilityToolsModule.getName(), utilityToolsModule);
                getLogger().info("✓ Loaded module: " + utilityToolsModule.getName());
            } else {
                getLogger().warning("✗ Failed to load module: " + utilityToolsModule.getName());
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

                updateChecker.runCheckAsync(result -> {
                    switch (result) {
                        case UP_TO_DATE ->
                                sender.sendMessage(
                                        "§aCeleryUtils is already up to date."
                                );

                        case UPDATE_AVAILABLE ->
                                sender.sendMessage(
                                        "§eUpdate found, but auto-download is disabled."
                                );

                        case UPDATE_DOWNLOADED ->
                                sender.sendMessage(
                                        "§aUpdate downloaded successfully! Restart the server to apply it."
                                );

                        case DOWNLOAD_FAILED ->
                                sender.sendMessage(
                                        "§cUpdate found, but download failed. Check console."
                                );

                        case ERROR ->
                                sender.sendMessage(
                                        "§cUpdate check failed. Check console."
                                );
                    }
                });

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
            // Reload main config from disk before deciding module state
            reloadConfig();

            // Ensure module configs are present/merged with defaults
            upgradeConfig("config.yml", new File(getDataFolder(), "config.yml"), "main config");
            upgradeConfig("modules/discord-link/config.yml", new File(getDataFolder(), "modules/discord-link/config.yml"), "Discord Link module config");
            upgradeConfig("modules/discord-whitelist-channel/config.yml", new File(getDataFolder(), "modules/discord-whitelist-channel/config.yml"), "Discord Whitelist Channel module config");
            upgradeConfig("modules/economy-permissions/config.yml", new File(getDataFolder(), "modules/economy-permissions/config.yml"), "Economy Permissions module config");
            upgradeConfig("modules/death-penalty/config.yml", new File(getDataFolder(), "modules/death-penalty/config.yml"), "Death Penalty module config");
            upgradeConfig("modules/pvp-module/config.yml", new File(getDataFolder(), "modules/pvp-module/config.yml"), "PvP module config");
            upgradeConfig("modules/totemenhancements/config.yml", new File(getDataFolder(), "modules/totemenhancements/config.yml"), "TotemEnhancements module config");
            upgradeConfig("modules/essentials/config.yml", new File(getDataFolder(), "modules/essentials/config.yml"), "Utility Tools module config");
            saveResourceIfAbsent("modules/essentials/motds.yml");
            saveResourceIfAbsent("modules/essentials/tips.yml");

            // Reload plugin config again after upgrade so the latest values are loaded.
            reloadConfig();

            // Determine desired enabled state from config
            boolean wantDiscord = isModuleEnabled("modules.discord-link.enabled", "modules.discord-sync.enabled");
            boolean wantWhitelist = getConfig().getBoolean("modules.discord-whitelist-channel.enabled", false);
            boolean wantEcon = getConfig().getBoolean("modules.economy-permissions.enabled", true);
            boolean wantDeath = getConfig().getBoolean("modules.death-penalty.enabled", true);
            boolean wantPvp = getConfig().getBoolean("modules.pvp-module.enabled", true);
            boolean wantTotem = getConfig().getBoolean("modules.totemenhancements.enabled", true);
            boolean wantUtilityTools = isModuleEnabled("modules.essentials.enabled", "modules.afk.enabled");

            reloadModule("Discord Link", wantDiscord, () -> new DiscordLinkModule(this));
            reloadModule("Discord Whitelist Channel", wantWhitelist, () -> new DiscordWhitelistChannelModule(this));
            reloadModule("Economy Permissions", wantEcon, () -> new EconomyPermissionsModule(this));
            reloadModule("Death Penalty", wantDeath, () -> new DeathPenaltyModule(this));
            reloadModule("pvp-module", wantPvp, () -> new PvPModule(this));
            reloadModule("totemenhancements", wantTotem, () -> new TotemEnhancementsModule(this));
            reloadModule("essentials", wantUtilityTools, () -> new EssentialsModule(this));

            CeleryModule utilModule = modules.get("essentials");
            if (utilModule instanceof EssentialsModule ucm) {
                for (String warning : ucm.getMotdInitWarnings()) {
                    sender.sendMessage("§e" + warning);
                }
            }

            sender.sendMessage("§aReload complete.");
        } catch (Exception e) {
            sender.sendMessage("§cFailed to reload configs/modules: " + e.getMessage());
            getLogger().log(Level.WARNING, "Failed to reload configs/modules", e);
        }
    }

    private void reloadModule(String name, boolean shouldEnable, Supplier<CeleryModule> factory) {
        CeleryModule module = getModule(name);
        if (!shouldEnable) {
            if (module != null) {
                try {
                    module.disable();
                } catch (Exception ignored) {
                }
                modules.remove(name);
                getLogger().info("Disabled module: " + name);
            }
            return;
        }

        if (module != null) {
            try {
                module.disable();
            } catch (Exception ignored) {
            }
            modules.remove(name);
        }

        CeleryModule newModule = factory.get();
        if (newModule.initialize()) {
            modules.put(newModule.getName(), newModule);
            getLogger().info("✓ Loaded module: " + newModule.getName());
        } else {
            getLogger().warning("✗ Failed to load module: " + newModule.getName());
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
        sender.sendMessage("§7Modules: §aDiscord Link, Discord Whitelist Channel, Economy Permissions, Death Penalty, PvP Module, TotemEnhancements, Utility Tools");
        sender.sendMessage("§7For more details use §b/cu help modules §7or §b/cu help [link|ecoperm|whitelist|utility|admin]§7.");
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
            case "whitelist", "discord whitelist", "discord-whitelist", "discordwhitelist" -> {
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
            case "modules", "module" -> {
                sender.sendMessage("§b§lCeleryUtils §7- §fModules");
                sender.sendMessage("§fDiscord Link §7- §7Link Minecraft players to Discord and sync nicknames.");
                sender.sendMessage("§fDiscord Whitelist Channel §7- §7Auto-whitelist players from names posted in a Discord channel.");
                sender.sendMessage("§fEconomy Permissions §7- §7Sell permission rules via economy and configurable buyable permissions.");
                sender.sendMessage("§fDeath Penalty §7- §7Apply XP and/or money penalties when players die with keepInventory enabled.");
                sender.sendMessage("§fPvP Module §7- §7Enable toggleable PvP mode with saved gear loadouts via /pvp.");
                sender.sendMessage("§fTotemEnhancements §7- §7Enhances totems with inventory activation, death broadcasts, and more.");
                sender.sendMessage("§fUtility Tools §7- §7Includes /afk and /killall with auto AFK detection and cleanup controls.");
            }
            case "utility", "afk", "killall", "tips" -> {
                sender.sendMessage("§b§lCeleryUtils §7- §fUtility Tools Help");
                sender.sendMessage("§f/afk §7- §7Toggle your AFK state manually");
                sender.sendMessage("§f/killall <target> [world] §7- §7Remove entities by selector or exact type");
                sender.sendMessage("§f/tips [page] §7- §7Browse configurable server tips with a paged interface");
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("celeryutils")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>();
            subcommands.add("help");
            subcommands.add("status");
            subcommands.add("link");
            subcommands.add("ecoperm");
            if (sender.hasPermission("celeryutils.admin")) {
                subcommands.add("reload");
                subcommands.add("update");
            }
            return partialMatch(args[0], subcommands);
        }

        if (args.length == 2) {
            String firstArg = args[0].toLowerCase();
            if (firstArg.equals("help")) {
                return partialMatch(args[1], List.of("modules", "link", "whitelist", "ecoperm", "utility", "status", "admin"));
            }
            if (firstArg.equals("ecoperm")) {
                return partialMatch(args[1], List.of("buy", "list"));
            }
        }

        return Collections.emptyList();
    }

    private List<String> partialMatch(String token, List<String> options) {
        if (token == null || token.isEmpty()) {
            return new ArrayList<>(options);
        }
        String lowerToken = token.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lowerToken)) {
                matches.add(option);
            }
        }
        return matches;
    }
}