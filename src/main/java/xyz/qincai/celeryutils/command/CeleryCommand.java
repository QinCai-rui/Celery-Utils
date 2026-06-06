package xyz.qincai.celeryutils.command;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;

public interface CeleryCommand extends CommandExecutor, TabCompleter {
    String getName();
    String getDescription();
    String getPermission();
}
