package xyz.qincai.celeryutils.modules;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
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
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

public class EssentialsModule implements CeleryModule, Listener, CommandExecutor, TabCompleter {

    private final CeleryUtils plugin;
    private final Map<UUID, Long> lastActivityMillis = new HashMap<>();
    private final Map<UUID, Long> afkSinceMillis = new HashMap<>();
    private final Map<UUID, String> originalTabNames = new HashMap<>();
    private final Set<UUID> afkPlayers = new HashSet<>();
    private final Set<UUID> manuallyAfk = new HashSet<>();
    private final Random random = new Random();
    private BukkitTask afkTask;
    private FileConfiguration config;
    private PluginCommand afkCommand;
    private PluginCommand killallCommand;
    private PluginCommand gmCommand;
    private PluginCommand tempbanCommand;
    private PluginCommand kickallCommand;
    private final Map<UUID, BukkitTask> tempbanTasks = new HashMap<>();

    private List<Component> motdComponents = Collections.emptyList();
    private int motdCurrentIndex;
    private BukkitTask motdRotationTask;
    private final List<String> motdInitWarnings = new ArrayList<>();

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

    public EssentialsModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "essentials";
    }

    @Override
    public boolean initialize() {
        File configFile = new File(plugin.getDataFolder(), "modules/essentials/config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("modules/essentials/config.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(configFile);

        if (!setupCommands()) {
            return false;
        }

        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(this, plugin);

        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            lastActivityMillis.put(player.getUniqueId(), now);
        }

        if (config.getBoolean("afk.enabled", true)) {
            startAfkTask();
        }

        initializeMotd();
        return true;
    }

    private boolean setupCommands() {
        CommandMap commandMap = Bukkit.getCommandMap();

        setupSingleCommand("afk", "afk.command-enabled", true,
                cmd -> this.afkCommand = cmd,
                () -> this.afkCommand);
        setupSingleCommand("killall", "killall.enabled", true,
                cmd -> this.killallCommand = cmd,
                () -> this.killallCommand);
        setupSingleCommand("gm", null, null,
                cmd -> this.gmCommand = cmd,
                () -> this.gmCommand);
        setupSingleCommand("tempban", "tempban.enabled", true,
                cmd -> this.tempbanCommand = cmd,
                () -> this.tempbanCommand);
        setupSingleCommand("kickall", "kickall.enabled", true,
                cmd -> this.kickallCommand = cmd,
                () -> this.kickallCommand);

        return true;
    }

    private void setupSingleCommand(String name, String configPath, Boolean configDefault,
                                     Consumer<PluginCommand> setter, Supplier<PluginCommand> getter) {
        boolean enabled = configPath == null || config.getBoolean(configPath, configDefault);
        if (enabled) {
            PluginCommand cmd = plugin.getPluginCommand(name);
            if (cmd != null) {
                setter.accept(cmd);
                CommandMap commandMap = Bukkit.getCommandMap();
                ensureCommandRegistered(commandMap, name, cmd);
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
                return;
            }
            plugin.getLogger().warning("Command /" + name + " is missing in plugin.yml");
        } else {
            // Unregister to clean up from a previous registration during reload
            plugin.unregisterCommand(name);
            PluginCommand cmd = getter.get();
            if (cmd != null) {
                cmd.setExecutor(null);
                cmd.setTabCompleter(null);
            }
            setter.accept(null);
        }
    }

    private void ensureCommandRegistered(CommandMap commandMap, String name, PluginCommand command) {
        Command existing = commandMap.getCommand(name);
        if (command.equals(existing)) {
            return;
        }
        command.unregister(commandMap);
        commandMap.register(name, plugin.getName(), command);
    }

    @Override
    public void disable() {
        // Unregister commands from the CommandMap so other plugins can use them
        CommandMap commandMap = Bukkit.getCommandMap();
        if (afkCommand != null) {
            afkCommand.unregister(commandMap);
        }
        if (killallCommand != null) {
            killallCommand.unregister(commandMap);
        }
        if (gmCommand != null) {
            gmCommand.unregister(commandMap);
        }
        if (tempbanCommand != null) {
            tempbanCommand.unregister(commandMap);
        }
        if (kickallCommand != null) {
            kickallCommand.unregister(commandMap);
        }

        for (BukkitTask task : tempbanTasks.values()) {
            task.cancel();
        }
        tempbanTasks.clear();

        if (afkTask != null) {
            afkTask.cancel();
            afkTask = null;
        }

        disableMotd();

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
        return plugin.getConfig().getBoolean("modules.essentials.enabled", plugin.getConfig().getBoolean("modules.afk.enabled", false));
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
        if (command.getName().equalsIgnoreCase("gm")) {
            return handleGamemodeCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("tempban")) {
            return handleTempbanCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("kickall")) {
            return handleKickallCommand(sender, args);
        }
        return false;
    }

    private boolean handleAfkCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /afk."));
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

    private boolean handleGamemodeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /gm."));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(color("&cUsage: /gm <0|1|2|3|survival|creative|adventure|spectator>"));
            return true;
        }

        String modeArg = args[0].toLowerCase(Locale.ROOT);
        GameMode mode;

        switch (modeArg) {
            case "0", "s", "survival" -> mode = GameMode.SURVIVAL;
            case "1", "c", "creative" -> mode = GameMode.CREATIVE;
            case "2", "a", "adventure" -> mode = GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> mode = GameMode.SPECTATOR;
            default -> {
                player.sendMessage(color("&cUnknown gamemode: &f" + args[0]));
                return true;
            }
        }

        player.setGameMode(mode);
        player.sendMessage(color("&aGamemode set to &f" + mode.name().toLowerCase(Locale.ROOT)));
        return true;
    }

    private boolean handleTempbanCommand(CommandSender sender, String[] args) {
        if (!config.getBoolean("tempban.enabled", true)) {
            sendMsg(sender, config.getString("messages.tempban-usage", "<red>Usage:</red> <white>/tempban <player> <duration> [reason]</white>"));
            return true;
        }

        String permission = config.getString("tempban.command-permission", "celeryutils.tempban");
        if (permission != null && !permission.isBlank() && !sender.hasPermission(permission)) {
            sender.sendMessage(color(config.getString("messages.no-permission", "&cYou do not have permission to use this command.")));
            return true;
        }

        if (args.length < 2) {
            sendMsg(sender, config.getString("messages.tempban-usage", "<red>Usage:</red> <white>/tempban <player> <duration> [reason]</white>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(color(config.getString("messages.tempban-invalid-player", "&cPlayer &f%player% &cnot found.")
                    .replace("%player%", args[0])));
            return true;
        }

        // Parse duration string (e.g. "10m", "2h30m", "1d", "5m", or bare number = minutes)
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

        // Build ban reason
        String reason = args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : config.getString("tempban.message-reason", "Temporary ban");

        String senderName = sender instanceof Player ? sender.getName() : "Console";
        long expiry = System.currentTimeMillis() + durationMillis;

        // Cancel any existing unban task for this player
        BukkitTask existingTask = tempbanTasks.remove(target.getUniqueId());
        if (existingTask != null) {
            existingTask.cancel();
        }

        // Perform the ban
        target.banPlayer(reason, java.util.Date.from(java.time.Instant.ofEpochMilli(expiry)), senderName);

        // Schedule automatic unban
        BukkitTask unbanTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            target.getServer().getBanList(org.bukkit.BanList.Type.NAME).pardon(target.getName());
            tempbanTasks.remove(target.getUniqueId());
        }, ticks);
        tempbanTasks.put(target.getUniqueId(), unbanTask);

        // Notify target
        String bannedMsg = config.getString("messages.tempban-banned",
                "<yellow>You have been tempbanned by <white>%sender%</white> for <white>%duration%</white>.\n<yellow>Reason:</yellow> <white>%reason%</white>")
                .replace("%sender%", senderName)
                .replace("%duration%", durationStr)
                .replace("%reason%", reason);
        sendMsg(target, bannedMsg);

        // Notify sender
        String successMsg = config.getString("messages.tempban-success", "<green>Tempbanned <white>%player%</white> for <white>%duration%</white>.</green>")
                .replace("%player%", target.getName())
                .replace("%duration%", durationStr);
        sendMsg(sender, successMsg);

        return true;
    }

    private boolean handleKickallCommand(CommandSender sender, String[] args) {
        if (!config.getBoolean("kickall.enabled", true)) {
            sender.sendMessage(color(config.getString("messages.kickall-usage", "&cUsage: /kickall [reason]")));
            return true;
        }

        String permission = config.getString("kickall.command-permission", "celeryutils.kickall");
        if (permission != null && !permission.isBlank() && !sender.hasPermission(permission)) {
            sender.sendMessage(color(config.getString("messages.no-permission", "&cYou do not have permission to use this command.")));
            return true;
        }

        String reason = args.length > 0
                ? String.join(" ", args)
                : config.getString("kickall.message-reason", "Kicked by operator");

        boolean includeOps = config.getBoolean("kickall.include-operators", false);
        String broadcastMsg = config.getString("kickall.broadcast-message", "<red>Server is kicking all players. Rejoin shortly.</red>");
        if (!broadcastMsg.isEmpty()) {
            Component broadcastComponent = MiniMessage.miniMessage().deserialize(broadcastMsg);
            plugin.getServer().broadcast(broadcastComponent);
        }

        // Snapshot online players; exclude the sender
        List<Player> toKick = new ArrayList<>(Bukkit.getOnlinePlayers());
        toKick.remove(sender);
        if (!includeOps) {
            toKick.removeIf(Player::isOp);
        }

        int count = toKick.size();
        if (count > 0) {
            String kickMsg = config.getString("messages.kickall-kicked", "<red>Kicked by operator:</red> <white>%reason%</white>")
                    .replace("%reason%", reason);
            Component kickComponent;
            try {
                kickComponent = MiniMessage.miniMessage().deserialize(kickMsg);
            } catch (Exception e) {
                kickComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(kickMsg);
            }
            // Brief delay so players see the broadcast before being disconnected
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player player : toKick) {
                    if (player.isOnline()) {
                        player.kick(kickComponent);
                    }
                }
            }, 2L);
        }

        String successMsg = config.getString("messages.kickall-success", "<green>Kicked <white>%count%</white> players.</green>")
                .replace("%count%", Integer.toString(count));
        Component successComponent;
        try {
            successComponent = MiniMessage.miniMessage().deserialize(successMsg);
        } catch (Exception e) {
            successComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(successMsg);
        }
        sender.sendMessage(successComponent);

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

    // Shows a detailed kick message for tempbanned players, using MiniMessage formatting
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.KICK_BANNED) {
            return;
        }
        org.bukkit.BanEntry banEntry = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntry(event.getPlayer().getName());
        if (banEntry == null || banEntry.getExpiration() == null) {
            return;
        }
        long remaining = banEntry.getExpiration().getTime() - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        String timeStr = formatDuration(remaining);
        String reason = banEntry.getReason();
        String message = "<red>You are temporarily banned.</red>\n";
        if (reason != null && !reason.isBlank()) {
            message += "<red>Reason:</red> <white>" + reason + "</white>\n";
        }
        message += "<red>Remaining time:</red> <yellow>" + timeStr + "</yellow>";
        event.kickMessage(MiniMessage.miniMessage().deserialize(message));
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
        if (command.getName().equalsIgnoreCase("gm")) {
            return partialMatch(args[0], List.of("0","1","2","3","survival","creative","adventure","spectator"));
        }
        if (command.getName().equalsIgnoreCase("tempban")) {
            if (args.length == 1) {
                List<String> players = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    players.add(player.getName());
                }
                return partialMatch(args[0], players);
            }
            return Collections.emptyList();
        }
        if (command.getName().equalsIgnoreCase("kickall")) {
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

    private void sendMsg(CommandSender sender, String message) {
        try {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(message));
        } catch (Exception e) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
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

    private long parseDuration(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Empty duration");
        }

        // Bare number → minutes (backwards compat)
        if (input.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(input) * 60_000L;
        }

        long total = 0;
        StringBuilder num = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (Character.isLetter(c)) {
                if (num.isEmpty()) {
                    throw new IllegalArgumentException("No number before unit");
                }
                long value = Long.parseLong(num.toString());
                num.setLength(0);

                StringBuilder unit = new StringBuilder();
                unit.append(c);
                while (i + 1 < input.length() && Character.isLetter(input.charAt(i + 1))) {
                    unit.append(input.charAt(i + 1));
                    i++;
                }

                String unitStr = unit.toString().toLowerCase(Locale.ROOT);
                switch (unitStr) {
                    case "s":
                    case "sec":
                    case "secs":
                    case "second":
                    case "seconds":
                        total += value * 1000L;
                        break;
                    case "m":
                    case "min":
                    case "mins":
                    case "minute":
                    case "minutes":
                        total += value * 60_000L;
                        break;
                    case "h":
                    case "hr":
                    case "hrs":
                    case "hour":
                    case "hours":
                        total += value * 3_600_000L;
                        break;
                    case "d":
                    case "day":
                    case "days":
                        total += value * 86_400_000L;
                        break;
                    case "w":
                    case "week":
                    case "weeks":
                        total += value * 604_800_000L;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown unit: " + unitStr);
                }
            } else {
                throw new IllegalArgumentException("Invalid character: " + c);
            }
        }

        if (num.length() > 0) {
            throw new IllegalArgumentException("Trailing number without unit");
        }

        return total;
    }

    private String miniMessageToLegacy(String message) {
        return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(message == null ? "" : message));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(ServerListPingEvent event) {
        if (!config.getBoolean("motd.enabled", false)) {
            return;
        }
        if (motdComponents.isEmpty()) {
            return;
        }

        int mode = config.getString("rotation-mode", "SEQUENTIAL").equalsIgnoreCase("RANDOM") ? 1 : 0;
        Component motd;
        if (mode == 1) {
            motd = motdComponents.get(random.nextInt(motdComponents.size()));
        } else {
            motd = motdComponents.get(motdCurrentIndex % motdComponents.size());
        }
        event.motd(motd);
    }

    private void initializeMotd() {
        motdInitWarnings.clear();
        if (!config.getBoolean("motd.enabled", false)) {
            return;
        }

        loadMotdMessages();

        int interval = config.getInt("motd.rotation-interval-seconds", 0);
        if (interval > 0 && motdComponents.size() > 1) {
            motdRotationTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                motdCurrentIndex = (motdCurrentIndex + 1) % motdComponents.size();
            }, interval * 20L, interval * 20L);
        }
    }

    private void disableMotd() {
        if (motdRotationTask != null) {
            motdRotationTask.cancel();
            motdRotationTask = null;
        }
        motdComponents = Collections.emptyList();
        motdCurrentIndex = 0;
    }

    private void loadMotdMessages() {
        String messagesFile = config.getString("motd.messages-file", "motds.yml");
        if (messagesFile == null || messagesFile.isBlank()) {
            messagesFile = "motds.yml";
        }
        messagesFile = messagesFile.trim();

        File file = new File(plugin.getDataFolder(), "modules/essentials/" + messagesFile);
        if (!file.exists()) {
            motdComponents = Collections.emptyList();
            return;
        }

        List<?> rawList;
        if (messagesFile.endsWith(".yml") || messagesFile.endsWith(".yaml")) {
            rawList = YamlConfiguration.loadConfiguration(file).getList("messages");
        } else {
            try {
                List<String> lines = new ArrayList<>();
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) lines.add(line);
                }
                rawList = lines;
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to read MOTD file: " + messagesFile, e);
                motdComponents = Collections.emptyList();
                return;
            }
        }

        if (rawList == null || rawList.isEmpty()) {
            motdComponents = Collections.emptyList();
            return;
        }

        List<Component> components = new ArrayList<>();
        for (Object entry : rawList) {
            if (entry instanceof String s && !s.isBlank()) {
                Component component = parseMotdComponent(s);
                if (component != null) components.add(component);
            }
        }

        motdComponents = components.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(components);
    }

    private Component parseMotdComponent(String text) {
        if (text == null || text.isBlank()) {
            return Component.empty();
        }
        String processed = text.replace("\n", "<newline>");
        try {
            return MiniMessage.miniMessage().deserialize(processed);
        } catch (Exception e) {
            String warning = "MOTD line contains legacy formatting codes, using legacy fallback: \"" + text + "\"";
            motdInitWarnings.add(warning);
            plugin.getLogger().warning(warning);
            String legacy = ChatColor.translateAlternateColorCodes('&', processed);
            return LegacyComponentSerializer.legacySection().deserialize(legacy);
        }
    }

    public List<String> getMotdInitWarnings() {
        List<String> warnings = new ArrayList<>(motdInitWarnings);
        motdInitWarnings.clear();
        return warnings;
    }
}
