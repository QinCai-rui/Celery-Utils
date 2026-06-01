package xyz.qincai.celeryutils.modules;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public class UtilityCommandsModule implements CeleryModule, Listener, CommandExecutor, TabCompleter {

    private final CeleryUtils plugin;
    private final Map<UUID, Long> lastActivityMillis = new HashMap<>();
    private final Map<UUID, Long> afkSinceMillis = new HashMap<>();
    private final Map<UUID, String> originalTabNames = new HashMap<>();
    private final Set<UUID> afkPlayers = new HashSet<>();
    private final Set<UUID> manuallyAfk = new HashSet<>();
    private BukkitTask afkTask;
    private FileConfiguration config;

    private static final List<String> KILLALL_SELECTORS = List.of(
            "items",
            "hostile",
            "animal",
            "villager",
            "golem",
            "iron_golem",
            "water",
            "ambient",
            "projectiles",
            "vehicles",
            "experience",
            "mobs",
            "all"
    );

    public UtilityCommandsModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "utility-tools";
    }

    @Override
    public boolean initialize() {
        File configFile = new File(plugin.getDataFolder(), "modules/utility-tools/config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("modules/utility-tools/config.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(configFile);

        PluginCommand afkCommand = plugin.getCommand("afk");
        PluginCommand killallCommand = plugin.getCommand("killall");
        if (afkCommand == null || killallCommand == null) {
            plugin.getLogger().warning("Utility Tools commands are missing in plugin.yml");
            return false;
        }

        afkCommand.setExecutor(this);
        afkCommand.setTabCompleter(this);
        killallCommand.setExecutor(this);
        killallCommand.setTabCompleter(this);

        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(this, plugin);

        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            lastActivityMillis.put(player.getUniqueId(), now);
        }

        if (config.getBoolean("afk.enabled", true)) {
            startAfkTask();
        }
        return true;
    }

    @Override
    public void disable() {
        if (afkTask != null) {
            afkTask.cancel();
            afkTask = null;
        }

        for (UUID uuid : new HashSet<>(afkPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                setAfk(player, false, false);
            }
        }
        afkPlayers.clear();
        manuallyAfk.clear();
        afkSinceMillis.clear();
        lastActivityMillis.clear();
        originalTabNames.clear();
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("modules.utility-tools.enabled", plugin.getConfig().getBoolean("modules.afk.enabled", false));
    }

    private void startAfkTask() {
        if (afkTask != null) {
            afkTask.cancel();
        }
        afkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runAfkTick, 20L, 20L);
    }

    private void runAfkTick() {
        if (!config.getBoolean("afk.enabled", true)) {
            return;
        }

        int timeoutSeconds = Math.max(1, config.getInt("afk.timeout-seconds", 300));
        int kickTimeoutSeconds = config.getInt("afk.kick-timeout-seconds", -1);
        String kickBypass = config.getString("afk.kick-bypass-permission", "celeryutils.afk.bypass");
        String kickMessage = ChatColor.translateAlternateColorCodes('&', config.getString("messages.afk-kick", "&cKicked for being AFK too long."));

        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            long lastActivity = lastActivityMillis.getOrDefault(uuid, now);

            if (!afkPlayers.contains(uuid) && (now - lastActivity) >= (timeoutSeconds * 1000L)) {
                setAfk(player, true, false);
            }

            if (kickTimeoutSeconds < 0 || !afkPlayers.contains(uuid)) {
                continue;
            }
            if (kickBypass != null && !kickBypass.isBlank() && player.hasPermission(kickBypass)) {
                continue;
            }

            long afkSince = afkSinceMillis.getOrDefault(uuid, now);
            if ((now - afkSince) >= (kickTimeoutSeconds * 1000L)) {
                player.kickPlayer(kickMessage);
                clearAfkState(uuid);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("afk")) {
            return handleAfkCommand(sender);
        }
        if (command.getName().equalsIgnoreCase("killall")) {
            return handleKillAllCommand(sender, args);
        }
        return false;
    }

    private boolean handleAfkCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /afk.");
            return true;
        }
        if (!config.getBoolean("afk.command-enabled", true)) {
            player.sendMessage(color(config.getString("messages.afk-command-disabled", "&cAFK command is disabled.")));
            return true;
        }

        String permission = config.getString("afk.command-permission", "celeryutils.afk");
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            player.sendMessage(color(config.getString("messages.no-permission", "&cYou do not have permission to use this command.")));
            return true;
        }

        boolean currentlyAfk = afkPlayers.contains(player.getUniqueId());
        if (currentlyAfk) {
            setAfk(player, false, true);
            player.sendMessage(color(config.getString("messages.afk-disabled", "&aYou are no longer AFK.")));
        } else {
            setAfk(player, true, true);
            player.sendMessage(color(config.getString("messages.afk-enabled", "&eYou are now AFK.")));
        }
        return true;
    }

    private boolean handleKillAllCommand(CommandSender sender, String[] args) {
        if (!config.getBoolean("killall.enabled", true)) {
            sender.sendMessage(color(config.getString("messages.killall-disabled", "&cKillall command is disabled.")));
            return true;
        }

        String permission = config.getString("killall.command-permission", "celeryutils.killall");
        if (permission != null && !permission.isBlank() && !sender.hasPermission(permission)) {
            sender.sendMessage(color(config.getString("messages.no-permission", "&cYou do not have permission to use this command.")));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(color(config.getString("messages.killall-usage", "&cUsage: /killall <selector|entity_type> [world]")));
            return true;
        }

        String target = normalize(args[0]);
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
                if (entity instanceof Player) {
                    continue;
                }
                if (!allowNamed && entity instanceof LivingEntity living && living.customName() != null) {
                    continue;
                }
                if (!filter.test(entity)) {
                    continue;
                }
                entity.remove();
                removed++;
            }
        }

        sender.sendMessage(color(config.getString("messages.killall-result", "&aRemoved &f%count% &aentities for target &f%target%&a.")
                .replace("%count%", Integer.toString(removed))
                .replace("%target%", args[0])));
        return true;
    }

    private Collection<World> resolveWorlds(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            World world = Bukkit.getWorld(args[1]);
            if (world == null) {
                return Collections.emptyList();
            }
            return List.of(world);
        }
        if (sender instanceof Player player) {
            return List.of(player.getWorld());
        }
        return Bukkit.getWorlds();
    }

    private Predicate<Entity> resolveFilter(String target) {
        return switch (target) {
            case "items", "item", "drop", "drops", "dropped_items" -> entity -> entity instanceof Item;
            case "hostile", "hostiles", "monster", "monsters" -> entity -> entity instanceof Monster;
            case "animal", "animals", "passive", "passives" -> entity -> entity instanceof Animals;
            case "villager", "villagers" -> entity -> entity instanceof Villager || entity instanceof WanderingTrader;
            case "golem", "golems" -> entity -> entity instanceof Golem;
            case "iron_golem", "irongolem" -> entity -> entity.getType() == EntityType.IRON_GOLEM;
            case "water", "watermob", "watermobs", "aquatic" -> entity -> entity instanceof WaterMob;
            case "ambient" -> entity -> entity instanceof Ambient;
            case "projectile", "projectiles" -> entity -> entity instanceof Projectile;
            case "vehicle", "vehicles" -> entity -> entity instanceof Vehicle;
            case "xp", "experience", "experience_orbs", "orbs" -> entity -> entity instanceof ExperienceOrb;
            case "mob", "mobs" -> entity -> entity instanceof LivingEntity;
            case "all", "entities" -> entity -> !(entity instanceof Player);
            default -> {
                EntityType entityType = parseEntityType(target);
                if (entityType == null || entityType == EntityType.PLAYER) {
                    yield null;
                }
                yield entity -> entity.getType() == entityType;
            }
        };
    }

    private EntityType parseEntityType(String token) {
        try {
            return EntityType.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markActivity(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActivityMillis.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearAfkState(event.getPlayer().getUniqueId());
    }

    private void markActivity(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        lastActivityMillis.put(uuid, now);

        if (afkPlayers.contains(uuid) && !manuallyAfk.contains(uuid)) {
            setAfk(player, false, false);
            player.sendMessage(color(config.getString("messages.afk-disabled-auto", "&aYou are no longer AFK due to activity.")));
        }
    }

    private void clearAfkState(UUID uuid) {
        afkPlayers.remove(uuid);
        manuallyAfk.remove(uuid);
        afkSinceMillis.remove(uuid);
        lastActivityMillis.remove(uuid);
        originalTabNames.remove(uuid);
    }

    private void setAfk(Player player, boolean afk, boolean manualToggle) {
        UUID uuid = player.getUniqueId();
        if (afk) {
            if (afkPlayers.contains(uuid)) {
                if (manualToggle) {
                    manuallyAfk.add(uuid);
                }
                return;
            }
            afkPlayers.add(uuid);
            afkSinceMillis.put(uuid, System.currentTimeMillis());
            if (manualToggle) {
                manuallyAfk.add(uuid);
            } else {
                manuallyAfk.remove(uuid);
            }

            if (config.getBoolean("afk.tab-placeholder-enabled", true)) {
                String original = originalTabNames.get(uuid);
                if (original == null || original.isBlank()) {
                    original = player.getPlayerListName();
                    if (original == null || original.isBlank()) {
                        original = player.getName();
                    }
                }
                originalTabNames.put(uuid, original);
                String prefix = miniMessageToLegacy(config.getString("afk.tab-placeholder", "<gray>[AFK]</gray> "));
                player.setPlayerListName(prefix + original);
            }
            return;
        }

        afkPlayers.remove(uuid);
        afkSinceMillis.remove(uuid);
        manuallyAfk.remove(uuid);
        lastActivityMillis.put(uuid, System.currentTimeMillis());

        String previousName = originalTabNames.remove(uuid);
        if (previousName != null) {
            player.setPlayerListName(previousName);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("afk")) {
            return Collections.emptyList();
        }
        if (!command.getName().equalsIgnoreCase("killall")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(KILLALL_SELECTORS);
            for (EntityType type : EntityType.values()) {
                if (type == EntityType.UNKNOWN || type == EntityType.PLAYER) {
                    continue;
                }
                options.add(type.name().toLowerCase(Locale.ROOT));
            }
            return partialMatch(args[0], options);
        }
        if (args.length == 2) {
            List<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return partialMatch(args[1], worlds);
        }
        return Collections.emptyList();
    }

    private List<String> partialMatch(String token, List<String> options) {
        if (token == null || token.isEmpty()) {
            return new ArrayList<>(options);
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message == null ? "" : message);
    }

    private String miniMessageToLegacy(String message) {
        return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(message == null ? "" : message));
    }
}
