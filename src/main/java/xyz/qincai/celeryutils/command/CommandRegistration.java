package xyz.qincai.celeryutils.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.lang.reflect.Field;
import java.util.Map;

public final class CommandRegistration {

    private CommandRegistration() {}

    public static void syncCommands() {
        try {
            Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
        } catch (Exception ignored) {}
    }

    public static boolean unregister(String name) {
        CommandMap map = Bukkit.getCommandMap();
        if (map == null) return false;
        Command cmd = map.getCommand(name);
        if (cmd != null) {
            cmd.unregister(map);
            return true;
        }
        return false;
    }

    public static boolean unregisterIfOwned(String name, String owner) {
        CommandMap map = Bukkit.getCommandMap();
        if (map == null) return false;
        Command cmd = map.getCommand(name);
        if (cmd != null) {
            try {
                Field field = cmd.getClass().getDeclaredField("pluginName");
                field.setAccessible(true);
                String pluginName = (String) field.get(cmd);
                if (owner.equals(pluginName)) {
                    cmd.unregister(map);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static boolean isRegistered(String name) {
        CommandMap map = Bukkit.getCommandMap();
        return map != null && map.getCommand(name) != null;
    }

    @SuppressWarnings("unchecked")
    public static void registerAlias(String alias, Command command) {
        CommandMap map = Bukkit.getCommandMap();
        if (map == null) return;
        try {
            Field knownCmds = map.getClass().getDeclaredField("knownCommands");
            knownCmds.setAccessible(true);
            Map<String, Command> known = (Map<String, Command>) knownCmds.get(map);
            known.put(alias.toLowerCase(), command);
        } catch (Exception ignored) {}
    }
}
