package xyz.qincai.celeryutils.command;

import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import xyz.qincai.celeryutils.CeleryUtils;

import java.util.List;
import java.util.Locale;

public class GameModeCommand implements CeleryCommand {

    public GameModeCommand(CeleryUtils plugin, FileConfiguration config) {}

    @Override
    public String getName() { return "gm"; }

    @Override
    public String getDescription() { return "Quick gamemode switch"; }

    @Override
    public String getPermission() { return "celeryutils.gamemode"; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /gm.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("§cUsage: /gm <0|1|2|3|survival|creative|adventure|spectator>");
            return true;
        }
        String arg = args[0].toLowerCase(Locale.ROOT);
        GameMode mode = switch (arg) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "2", "a", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
        if (mode == null) {
            player.sendMessage("§cUnknown gamemode: §f" + args[0]);
            return true;
        }
        player.setGameMode(mode);
        player.sendMessage("§aGamemode set to §f" + mode.name().toLowerCase(Locale.ROOT));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return partialMatch(args[0], List.of("0","1","2","3","survival","creative","adventure","spectator"));
    }

    private List<String> partialMatch(String token, List<String> options) {
        if (token == null || token.isEmpty()) return new java.util.ArrayList<>(options);
        String t = token.toLowerCase(Locale.ROOT);
        java.util.List<String> matches = new java.util.ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) matches.add(o);
        }
        return matches;
    }
}
