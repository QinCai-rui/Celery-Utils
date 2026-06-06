package xyz.qincai.celeryutils.command;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import xyz.qincai.celeryutils.CeleryUtils;

import java.util.*;

public class KillAllCommand implements CeleryCommand {

    private final CeleryUtils plugin;
    private final FileConfiguration config;

    private static final List<String> SELECTORS = List.of(
            "items", "hostile", "animal", "villager", "golem", "iron_golem",
            "water", "ambient", "projectiles", "vehicles", "experience", "mobs", "all"
    );

    public KillAllCommand(CeleryUtils plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public String getName() { return "killall"; }

    @Override
    public String getDescription() { return "Remove entities by category or type"; }

    @Override
    public String getPermission() {
        return config.getString("killall.command-permission", "celeryutils.killall");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!config.getBoolean("killall.enabled", true)) {
            sender.sendMessage(color(config.getString("messages.killall-disabled", "&cKillall command is disabled.")));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(color(config.getString("messages.killall-usage", "&cUsage: /killall <selector|entity_type> [world]")));
            return true;
        }

        String target = args[0].toLowerCase(Locale.ROOT).replace('-', '_');
        Predicate<Entity> filter = resolveFilter(target);
        if (filter == null) {
            sender.sendMessage(color(config.getString("messages.killall-unknown-target", "&cUnknown killall target: &f%target%")
                    .replace("%target%", args[0])));
            return true;
        }

        Collection<World> worlds = resolveWorlds(sender, args);
        if (worlds.isEmpty()) {
            sender.sendMessage(color(config.getString("messages.killall-unknown-world", "&cUnknown world: &f%world%")
                    .replace("%world%", args.length > 1 ? args[1] : "")));
            return true;
        }

        int removed = 0;
        boolean allowNamed = config.getBoolean("killall.include-named-entities", false);
        for (World world : worlds) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (entity instanceof Player) continue;
                if (!allowNamed && entity instanceof LivingEntity living && living.customName() != null) continue;
                if (!filter.test(entity)) continue;
                entity.remove();
                removed++;
            }
        }

        sender.sendMessage(color(config.getString("messages.killall-result", "&aRemoved &f%count% &aentities for target &f%target%&a.")
                .replace("%count%", Integer.toString(removed))
                .replace("%target%", args[0])));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(SELECTORS);
            for (EntityType type : EntityType.values()) {
                if (type == EntityType.UNKNOWN || type == EntityType.PLAYER) continue;
                options.add(type.name().toLowerCase(Locale.ROOT));
            }
            return partialMatch(args[0], options);
        }
        if (args.length == 2) {
            List<String> worlds = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) worlds.add(w.getName());
            return partialMatch(args[1], worlds);
        }
        return List.of();
    }

    private Collection<World> resolveWorlds(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            World world = Bukkit.getWorld(args[1]);
            return world != null ? List.of(world) : List.of();
        }
        if (sender instanceof Player player) return List.of(player.getWorld());
        return Bukkit.getWorlds();
    }

    private Predicate<Entity> resolveFilter(String target) {
        return switch (target) {
            case "items", "item", "drop", "drops", "dropped_items" -> e -> e instanceof Item;
            case "hostile", "hostiles", "monster", "monsters" -> e -> e instanceof Monster;
            case "animal", "animals", "passive", "passives" -> e -> e instanceof Animals;
            case "villager", "villagers" -> e -> e instanceof Villager || e instanceof WanderingTrader;
            case "golem", "golems" -> e -> e instanceof Golem;
            case "iron_golem", "irongolem" -> e -> e.getType() == EntityType.IRON_GOLEM;
            case "water", "watermob", "watermobs", "aquatic" -> e -> e instanceof WaterMob;
            case "ambient" -> e -> e instanceof Ambient;
            case "projectile", "projectiles" -> e -> e instanceof Projectile;
            case "vehicle", "vehicles" -> e -> e instanceof Vehicle;
            case "xp", "experience", "experience_orbs", "orbs" -> e -> e instanceof ExperienceOrb;
            case "mob", "mobs" -> e -> e instanceof LivingEntity;
            case "all", "entities" -> e -> !(e instanceof Player);
            default -> {
                try {
                    EntityType et = EntityType.valueOf(target.toUpperCase(Locale.ROOT));
                    if (et == EntityType.PLAYER) yield null;
                    yield (Predicate<Entity>) e -> e.getType() == et;
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
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

    private String color(String msg) {
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', msg == null ? "" : msg);
    }

    @FunctionalInterface
    private interface Predicate<T> {
        boolean test(T t);
    }
}
