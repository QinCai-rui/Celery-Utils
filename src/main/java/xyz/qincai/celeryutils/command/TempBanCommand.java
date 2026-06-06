package xyz.qincai.celeryutils.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import xyz.qincai.celeryutils.CeleryUtils;

import java.util.*;
import java.util.logging.Level;

public class TempBanCommand implements CeleryCommand {

    private final CeleryUtils plugin;
    private final FileConfiguration config;
    private final Map<UUID, BukkitTask> tasks;

    public TempBanCommand(CeleryUtils plugin, FileConfiguration config, Map<UUID, BukkitTask> tasks) {
        this.plugin = plugin;
        this.config = config;
        this.tasks = tasks;
    }

    @Override
    public String getName() { return "tempban"; }

    @Override
    public String getDescription() { return "Temporarily ban a player"; }

    @Override
    public String getPermission() {
        return config.getString("tempban.command-permission", "celeryutils.tempban");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!config.getBoolean("tempban.enabled", true)) {
            sender.sendMessage(parse(config.getString("messages.tempban-usage")));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(parse(config.getString("messages.tempban-usage")));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(color(config.getString("messages.tempban-invalid-player", "&cPlayer &f%player% &cnot found.")
                    .replace("%player%", args[0])));
            return true;
        }

        long durationMillis;
        try {
            durationMillis = parseDuration(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(color(config.getString("messages.tempban-invalid-duration", "&cInvalid duration: &f%input%")
                    .replace("%input%", args[1])));
            return true;
        }
        if (durationMillis <= 0) {
            sender.sendMessage(color("&cDuration must be greater than zero."));
            return true;
        }

        String durationStr = formatDuration(durationMillis);
        long ticks = durationMillis / 50L;
        String reason = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : config.getString("tempban.message-reason", "Temporary ban");
        String senderName = sender instanceof Player ? sender.getName() : "Console";
        long expiry = System.currentTimeMillis() + durationMillis;

        BukkitTask existing = tasks.remove(target.getUniqueId());
        if (existing != null) existing.cancel();

        target.banPlayer(reason, Date.from(java.time.Instant.ofEpochMilli(expiry)), senderName);

        BukkitTask unbanTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            target.getServer().getBanList(org.bukkit.BanList.Type.NAME).pardon(target.getName());
            tasks.remove(target.getUniqueId());
        }, ticks);
        tasks.put(target.getUniqueId(), unbanTask);

        String bannedMsg = config.getString("messages.tempban-banned",
                "<yellow>You have been tempbanned by <white>%sender%</white> for <white>%duration%</white>.\n<yellow>Reason:</yellow> <white>%reason%</white>")
                .replace("%sender%", senderName)
                .replace("%duration%", durationStr)
                .replace("%reason%", reason);
        target.sendMessage(parse(bannedMsg));

        String successMsg = config.getString("messages.tempban-success", "<green>Tempbanned <white>%player%</white> for <white>%duration%</white>.</green>")
                .replace("%player%", target.getName())
                .replace("%duration%", durationStr);
        sender.sendMessage(parse(successMsg));

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
            return partialMatch(args[0], players);
        }
        return List.of();
    }

    private long parseDuration(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Empty duration");
        if (input.chars().allMatch(Character::isDigit)) return Long.parseLong(input) * 60_000L;
        long total = 0;
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (Character.isLetter(c)) {
                if (num.isEmpty()) throw new IllegalArgumentException("No number before unit");
                long value = Long.parseLong(num.toString());
                num.setLength(0);
                StringBuilder unit = new StringBuilder().append(c);
                while (i + 1 < input.length() && Character.isLetter(input.charAt(i + 1))) {
                    unit.append(input.charAt(i + 1));
                    i++;
                }
                switch (unit.toString().toLowerCase(Locale.ROOT)) {
                    case "s","sec","secs","second","seconds" -> total += value * 1000L;
                    case "m","min","mins","minute","minutes" -> total += value * 60_000L;
                    case "h","hr","hrs","hour","hours" -> total += value * 3_600_000L;
                    case "d","day","days" -> total += value * 86_400_000L;
                    case "w","week","weeks" -> total += value * 604_800_000L;
                    default -> throw new IllegalArgumentException("Unknown unit: " + unit);
                }
            } else {
                throw new IllegalArgumentException("Invalid character: " + c);
            }
        }
        if (num.length() > 0) throw new IllegalArgumentException("Trailing number without unit");
        return total;
    }

    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    private Component parse(String msg) {
        try { return MiniMessage.miniMessage().deserialize(msg); }
        catch (Exception e) { return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(msg); }
    }

    private String color(String msg) {
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', msg == null ? "" : msg);
    }

    private List<String> partialMatch(String token, List<String> options) {
        if (token == null || token.isEmpty()) return new ArrayList<>(options);
        String t = token.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) matches.add(o);
        }
        return matches;
    }
}
