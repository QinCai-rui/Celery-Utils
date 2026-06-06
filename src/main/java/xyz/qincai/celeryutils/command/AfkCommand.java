package xyz.qincai.celeryutils.command;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.modules.EssentialsModule;

import java.util.List;

public class AfkCommand implements CeleryCommand {

    private final CeleryUtils plugin;
    private final FileConfiguration config;
    private final EssentialsModule module;

    public AfkCommand(CeleryUtils plugin, FileConfiguration config, EssentialsModule module) {
        this.plugin = plugin;
        this.config = config;
        this.module = module;
    }

    @Override
    public String getName() { return "afk"; }

    @Override
    public String getDescription() { return "Toggle AFK status"; }

    @Override
    public String getPermission() {
        return config.getString("afk.command-permission", "celeryutils.afk");
    }

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /afk."));
            return true;
        }
        if (!config.getBoolean("afk.enabled", true)) {
            player.sendMessage(color("&cAFK is disabled on this server."));
            return true;
        }
        if (!config.getBoolean("afk.command-enabled", true)) {
            player.sendMessage(color(config.getString("messages.afk-command-disabled", "&cAFK command is disabled.")));
            return true;
        }
        module.toggleAfk(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        return List.of();
    }

    private String color(String msg) {
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', msg == null ? "" : msg);
    }
}
