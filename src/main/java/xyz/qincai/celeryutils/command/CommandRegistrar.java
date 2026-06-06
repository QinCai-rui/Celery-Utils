package xyz.qincai.celeryutils.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import xyz.qincai.celeryutils.CeleryUtils;

import java.util.ArrayList;
import java.util.List;

public class CommandRegistrar {

    private final CeleryUtils plugin;
    private final List<Command> registered = new ArrayList<>();

    public CommandRegistrar(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    public void register(String name, String description, CeleryCommand executor, boolean enabled) {
        CommandMap map = Bukkit.getCommandMap();
        if (enabled) {
            Command cmd = new Command(name, description, "/" + name, List.of()) {
                @Override
                public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                    String perm = executor.getPermission();
                    if (perm != null && !perm.isBlank() && !sender.hasPermission(perm)) {
                        sender.sendMessage("§cYou do not have permission to use this command.");
                        return true;
                    }
                    return executor.onCommand(sender, this, commandLabel, args);
                }

                @Override
                public List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
                    return executor.onTabComplete(sender, this, alias, args);
                }
            };
            map.register(name, plugin.getName(), cmd);
            registered.add(cmd);
        } else {
            CommandRegistration.unregisterIfOwned(name, plugin.getName());
        }
    }

    public void unregisterAll() {
        CommandMap map = Bukkit.getCommandMap();
        for (Command cmd : registered) {
            cmd.unregister(map);
        }
        registered.clear();
    }
}
