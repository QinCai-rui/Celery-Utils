package xyz.qincai.celeryutils;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.qincai.celeryutils.api.CeleryModule;
import xyz.qincai.celeryutils.modules.DiscordLinkModule;
import xyz.qincai.celeryutils.modules.EconomyPermissionsModule;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Main plugin class for CeleryUtils.
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

        saveDefaultConfig();
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        saveModuleResource("modules/discord-link/config.yml");
        saveModuleResource("modules/economy-permissions/config.yml");

        initializeModules();

        getLogger().info("CeleryUtils enabled successfully!");
    }

    private void saveModuleResource(String resourcePath) {
        try {
            if ("modules/discord-link/config.yml".equals(resourcePath)) {
                java.io.File legacyFile = new java.io.File(getDataFolder(), "modules/discord-sync/config.yml");
                java.io.File newFile = new java.io.File(getDataFolder(), resourcePath);
                if (!newFile.exists() && legacyFile.exists()) {
                    return;
                }
            }

            java.io.File outFile = new java.io.File(getDataFolder(), resourcePath);
            if (outFile.exists()) {
                return;
            }

            java.io.File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            saveResource(resourcePath, false);
        } catch (Exception ignored) {
            getLogger().warning("Failed to save module resource: " + resourcePath);
        }
    }

    @Override
    public void onDisable() {
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

    public CeleryModule getModule(String name) {
        return modules.get(name);
    }

    public Map<String, CeleryModule> getModules() {
        return new HashMap<>(modules);
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
                sender.sendMessage("§6=== CeleryUtils Status ===");
                sender.sendMessage("§fVersion: §a" + getDescription().getVersion());
                sender.sendMessage("§fLoaded Modules: §a" + modules.size());
                for (CeleryModule module : modules.values()) {
                    sender.sendMessage("  § - " + module.getName() + " §f[" + (module.isEnabled() ? "§aENABLED" : "§cDISABLED") + "§f]");
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
                    sender.sendMessage("§cUsage: /celeryutils ecoperm <buy|list> [rule]");
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
                        sender.sendMessage("§cUsage: /celeryutils ecoperm buy <rule>");
                        return true;
                    }

                    econ.purchasePermission(player, args[2]);
                    return true;
                }

                sender.sendMessage("§cUnknown action: " + subAction + ". Use buy or list.");
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
                try {
                    price = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
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
                try {
                    seconds = Long.parseLong(args[2]);
                } catch (NumberFormatException e) {
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

    private DiscordLinkModule getDiscordLinkModule() {
        CeleryModule module = getModule("Discord Link");
        if (module instanceof DiscordLinkModule linkModule) {
            return linkModule;
        }
        return null;
    }

    private boolean isModuleEnabled(String primaryKey, String legacyKey) {
        if (getConfig().contains(primaryKey)) {
            return getConfig().getBoolean(primaryKey, true);
        }
        return getConfig().getBoolean(legacyKey, true);
    }

    private void sendHelp(CommandSender sender, int page) {
        switch (page) {
            case 1 -> {
                sender.sendMessage("§6=== CeleryUtils Help 1/3 ===");
                sender.sendMessage("§f/celeryutils status §7- Show loaded modules");
                sender.sendMessage("§f/celeryutils link §7- Start Discord linking with a 6-digit code");
                sender.sendMessage("§f/celeryutils help 2 §7- Discord Link help");
                sender.sendMessage("§f/celeryutils help 3 §7- Economy Permissions help");
            }
            case 2 -> {
                sender.sendMessage("§6=== CeleryUtils Help 2/3 ===");
                sender.sendMessage("§f/celeryutils link §7- Generate a 6-digit code in game");
                sender.sendMessage("§7Send that code to the Discord bot in DM or the configured channel.");
                sender.sendMessage("§7Once verified, your Discord nickname syncs to your Minecraft name.");
                sender.sendMessage("§f/celeryutils help link §7- Show this page");
            }
            case 3 -> {
                sender.sendMessage("§6=== CeleryUtils Help 3/3 ===");
                sender.sendMessage("§f/celeryutils ecoperm list §7- List purchasable permissions");
                sender.sendMessage("§f/celeryutils ecoperm buy <rule> §7- Buy a permission rule");
                sender.sendMessage("§f/celeryutils setprice <rule> <price> §7- Change a rule price");
                sender.sendMessage("§f/celeryutils setduration <rule> <seconds> §7- Change a rule duration");
            }
            default -> sender.sendMessage("§cHelp page not found. Use /celeryutils help 1-3 or /celeryutils help link.");
        }
    }

    private void sendHelpTopic(CommandSender sender, String topic) {
        switch (topic) {
            case "link" -> sendHelp(sender, 2);
            case "ecoperm", "economy", "permissions" -> sendHelp(sender, 3);
            case "status" -> {
                sender.sendMessage("§6=== CeleryUtils Help: Status ===");
                sender.sendMessage("§f/celeryutils status §7- Show which modules are loaded and enabled");
            }
            default -> sendHelp(sender, 1);
        }
    }
}