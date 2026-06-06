package xyz.qincai.celeryutils.command;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import xyz.qincai.celeryutils.modules.PvPModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PvPCommand implements CeleryCommand {

    private final PvPModule module;
    private final FileConfiguration config;

    public PvPCommand(PvPModule module, FileConfiguration config) {
        this.module = module;
        this.config = config;
    }

    @Override
    public String getName() { return "pvp"; }

    @Override
    public String getDescription() { return "Toggle PvP loadout or manage gear"; }

    @Override
    public String getPermission() { return "celeryutils.pvp"; }

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (player.isDead()) {
            player.sendMessage(ChatColor.RED + "You cannot do this while dead.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "Usage: /pvp <gear|toggle>");
            return true;
        }

        if (args[0].equalsIgnoreCase("gear")) {
            if (module.isActivePvpPlayer(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "You cannot edit your gear while PvP is active!");
                return true;
            }
            module.openGearGUI(player);
            return true;
        } else if (args[0].equalsIgnoreCase("toggle")) {
            if (module.isActivePvpPlayer(player.getUniqueId())) {
                module.untogglePvP(player);
            } else {
                module.togglePvP(player);
            }
            return true;
        }

        player.sendMessage(ChatColor.RED + "Usage: /pvp <gear|toggle>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        if (args.length == 1) {
            String token = args[0].toLowerCase();
            List<String> options = Arrays.asList("gear", "toggle");
            if (token.isEmpty()) return options;
            List<String> matches = new ArrayList<>();
            for (String option : options) {
                if (option.startsWith(token)) matches.add(option);
            }
            return matches;
        }
        return Collections.emptyList();
    }
}
