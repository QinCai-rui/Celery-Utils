package xyz.qincai.celeryutils.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import xyz.qincai.celeryutils.CeleryUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TipsCommand implements CeleryCommand {

    private final CeleryUtils plugin;
    private final FileConfiguration config;
    private List<Component> tipsList = Collections.emptyList();
    private Component tipsTitle;
    private int perPage = 5;

    public TipsCommand(CeleryUtils plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.config = config;
        loadTips();
    }

    @Override
    public String getName() { return "tips"; }

    @Override
    public String getDescription() { return "Display server tips and tricks"; }

    @Override
    public String getPermission() {
        return config.getString("tips.command-permission");
    }

    private void loadTips() {
        if (!config.getBoolean("tips.enabled", true)) {
            tipsList = Collections.emptyList();
            return;
        }

        String tipsFile = config.getString("tips.tips-file", "tips.yml");
        if (tipsFile == null || tipsFile.isBlank()) tipsFile = "tips.yml";

        File file = new File(plugin.getDataFolder(), "modules/essentials/" + tipsFile.trim());
        if (!file.exists()) {
            tipsList = Collections.emptyList();
            return;
        }

        perPage = Math.max(1, config.getInt("tips.per-page", 5));
        String titleStr = config.getString("tips.title", "<gold><bold>Tips & Tricks</bold></gold>");
        tipsTitle = parse(titleStr);

        List<String> rawTips = YamlConfiguration.loadConfiguration(file).getStringList("tips");
        if (rawTips.isEmpty()) {
            tipsList = Collections.emptyList();
            return;
        }

        List<Component> components = new ArrayList<>();
        for (String raw : rawTips) {
            if (raw != null && !raw.isBlank()) components.add(parse(raw));
        }
        tipsList = Collections.unmodifiableList(components);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /tips.");
            return true;
        }

        if (tipsList.isEmpty()) {
            player.sendMessage("§cNo tips are available.");
            return true;
        }

        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                player.sendMessage("§cInvalid page number: §f" + args[0]);
                return true;
            }
        }

        sendPage(player, page);
        return true;
    }

    private void sendPage(Player player, int page) {
        int totalPages = (tipsList.size() + perPage - 1) / perPage;
        page = Math.max(1, Math.min(page, totalPages));

        int from = (page - 1) * perPage;
        int to = Math.min(from + perPage, tipsList.size());

        Component separator = Component.text("─".repeat(Math.min(45, 45))).color(NamedTextColor.DARK_GRAY);

        player.sendMessage(tipsTitle);
        player.sendMessage(separator);

        for (int i = from; i < to; i++) {
            player.sendMessage(Component.text((i + 1) + ". ").color(NamedTextColor.GOLD).append(tipsList.get(i)));
        }

        if (totalPages <= 1) return;

        player.sendMessage(separator);

        Component prev = page > 1
                ? Component.text("  « Previous  ").color(NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/tips " + (page - 1)))
                        .hoverEvent(HoverEvent.showText(Component.text("Go to page " + (page - 1))))
                : Component.text("  « Previous  ").color(NamedTextColor.DARK_GRAY);

        Component pageIndicator = Component.text("Page " + page + "/" + totalPages).color(NamedTextColor.WHITE);

        Component next = page < totalPages
                ? Component.text("  Next »  ").color(NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/tips " + (page + 1)))
                        .hoverEvent(HoverEvent.showText(Component.text("Go to page " + (page + 1))))
                : Component.text("  Next »  ").color(NamedTextColor.DARK_GRAY);

        player.sendMessage(Component.join(JoinConfiguration.noSeparators(), prev, pageIndicator, next));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && !tipsList.isEmpty()) {
            int totalPages = (tipsList.size() + perPage - 1) / perPage;
            List<String> pages = new ArrayList<>();
            for (int i = 1; i <= totalPages; i++) pages.add(String.valueOf(i));
            return partialMatch(args[0], pages);
        }
        return List.of();
    }

    private Component parse(String msg) {
        try { return MiniMessage.miniMessage().deserialize(msg); }
        catch (Exception e) { return LegacyComponentSerializer.legacyAmpersand().deserialize(msg); }
    }

    private List<String> partialMatch(String token, List<String> options) {
        if (token == null || token.isEmpty()) return new ArrayList<>(options);
        String t = token.toLowerCase(java.util.Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(java.util.Locale.ROOT).startsWith(t)) matches.add(o);
        }
        return matches;
    }
}
