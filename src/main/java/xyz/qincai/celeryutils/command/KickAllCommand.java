package xyz.qincai.celeryutils.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import xyz.qincai.celeryutils.CeleryUtils;

import java.util.ArrayList;
import java.util.List;

public class KickAllCommand implements CeleryCommand {

    private final CeleryUtils plugin;
    private final FileConfiguration config;

    public KickAllCommand(CeleryUtils plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public String getName() { return "kickall"; }

    @Override
    public String getDescription() { return "Kick all players from the server"; }

    @Override
    public String getPermission() {
        return config.getString("kickall.command-permission", "celeryutils.kickall");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!config.getBoolean("kickall.enabled", true)) {
            sender.sendMessage(color(config.getString("messages.kickall-usage", "&cUsage: /kickall [reason]")));
            return true;
        }

        String reason = args.length > 0
                ? String.join(" ", args)
                : config.getString("kickall.message-reason", "Kicked by operator");
        boolean includeOps = config.getBoolean("kickall.include-operators", false);

        String broadcastMsg = config.getString("kickall.broadcast-message", "");
        if (!broadcastMsg.isEmpty()) {
            plugin.getServer().broadcast(parse(broadcastMsg));
        }

        List<Player> toKick = new ArrayList<>(Bukkit.getOnlinePlayers());
        toKick.remove(sender);
        if (!includeOps) toKick.removeIf(Player::isOp);

        int count = toKick.size();
        if (count > 0) {
            String kickMsg = config.getString("messages.kickall-kicked", "<red>Kicked by operator:</red> <white>%reason%</white>")
                    .replace("%reason%", reason);
            Component kickComponent = parse(kickMsg);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player player : toKick) {
                    if (player.isOnline()) player.kick(kickComponent);
                }
            }, 2L);
        }

        String successMsg = config.getString("messages.kickall-success", "<green>Kicked <white>%count%</white> players.</green>")
                .replace("%count%", Integer.toString(count));
        sender.sendMessage(parse(successMsg));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private Component parse(String msg) {
        try { return MiniMessage.miniMessage().deserialize(msg); }
        catch (Exception e) { return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(msg); }
    }

    private String color(String msg) {
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', msg == null ? "" : msg);
    }
}
