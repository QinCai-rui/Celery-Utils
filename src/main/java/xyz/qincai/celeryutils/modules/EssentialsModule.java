package xyz.qincai.celeryutils.modules;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import java.time.Duration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import xyz.qincai.celeryutils.command.CommandRegistrar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;
import xyz.qincai.celeryutils.command.AfkCommand;
import xyz.qincai.celeryutils.command.GameModeCommand;
import xyz.qincai.celeryutils.command.KickAllCommand;
import xyz.qincai.celeryutils.command.KillAllCommand;
import xyz.qincai.celeryutils.command.TempBanCommand;
import xyz.qincai.celeryutils.command.TipsCommand;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class EssentialsModule implements CeleryModule, Listener {

    private final CeleryUtils plugin;
    private final Map<UUID, Long> lastActivityMillis = new HashMap<>();
    private final Map<UUID, Long> afkSinceMillis = new HashMap<>();
    private final Map<UUID, String> originalTabNames = new HashMap<>();
    private final Set<UUID> afkPlayers = new HashSet<>();
    private final Set<UUID> manuallyAfk = new HashSet<>();
    private final Random random = new Random();
    private BukkitTask afkTask;
    private FileConfiguration config;
    private CommandRegistrar registrar;
    private final Map<UUID, BukkitTask> tempbanTasks = new HashMap<>();

    private List<Component> motdComponents = Collections.emptyList();
    private int motdCurrentIndex;
    private BukkitTask motdRotationTask;
    private final List<String> motdInitWarnings = new ArrayList<>();

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

        this.registrar = new CommandRegistrar(plugin);
        registerCommands();

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

    private void registerCommands() {
        boolean afkEnabled = config.getBoolean("afk.enabled", true)
                && config.getBoolean("afk.command-enabled", true);
        registrar.register("afk", "Toggle AFK status", new AfkCommand(plugin, config, this), afkEnabled);
        registrar.register("killall", "Remove entities by category or type", new KillAllCommand(plugin, config), config.getBoolean("killall.enabled", true));
        registrar.register("gm", "Quick gamemode switch", new GameModeCommand(plugin, config), true);
        registrar.register("tempban", "Temporarily ban a player", new TempBanCommand(plugin, config, tempbanTasks), config.getBoolean("tempban.enabled", true));
        registrar.register("kickall", "Kick all players from the server", new KickAllCommand(plugin, config), config.getBoolean("kickall.enabled", true));
        registrar.register("tips", "Display server tips and tricks", new TipsCommand(plugin, config), config.getBoolean("tips.enabled", true));
    }

    @Override
    public void disable() {
        if (registrar != null) registrar.unregisterAll();

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
        boolean titleEnabled = config.getBoolean("afk.title-enabled", true);
        Component afkTitle = titleEnabled ? MiniMessage.miniMessage().deserialize(config.getString("afk.title-text", "<red><bold>YOU ARE AFK</bold></red>")) : null;
        Component afkSubtitle = titleEnabled ? MiniMessage.miniMessage().deserialize(config.getString("afk.subtitle-text", "<gray>Move to return</gray>")) : null;
        Title.Times afkTimes = titleEnabled ? Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(30), Duration.ofMillis(500)) : null;

        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            long lastActivity = lastActivityMillis.getOrDefault(uuid, now);

            boolean isAfk = afkPlayers.contains(uuid);

            if (!isAfk && (now - lastActivity) >= (timeoutSeconds * 1000L)) {
                setAfk(player, true, false);
                isAfk = true;
            }

            if (isAfk) {
                if (titleEnabled && afkTitle != null) {
                    player.showTitle(Title.title(afkTitle, afkSubtitle, afkTimes));
                }

                if (kickTimeoutSeconds >= 0) {
                    if (kickBypass == null || kickBypass.isBlank() || !player.hasPermission(kickBypass)) {
                        long afkSince = afkSinceMillis.getOrDefault(uuid, now);
                        if ((now - afkSince) >= (kickTimeoutSeconds * 1000L)) {
                            player.kickPlayer(kickMessage);
                            clearAfkState(uuid);
                        }
                    }
                }
            }
        }
    }

    public void toggleAfk(Player player) {
        boolean currentlyAfk = afkPlayers.contains(player.getUniqueId());
        if (currentlyAfk) {
            setAfk(player, false, true);
            player.sendMessage(color(config.getString("messages.afk-disabled", "&aYou are no longer AFK.")));
        } else {
            setAfk(player, true, true);
            player.sendMessage(color(config.getString("messages.afk-enabled", "&eYou are now AFK.")));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;

        Player player = event.getPlayer();

        boolean moved = event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ();

        if (moved) {
            markActivity(player);
            return;
        }

        if (config.getBoolean("afk.un-afk-on-camera-move", false)) {
            if (event.getFrom().getYaw() != event.getTo().getYaw()
                    || event.getFrom().getPitch() != event.getTo().getPitch()) {
                markActivity(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemHeld(PlayerItemHeldEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markActivity(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        markActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!config.getBoolean("afk.protection.invulnerable", false)) return;
        if (afkPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetEvent event) {
        if (!config.getBoolean("afk.protection.no-mob-targeting", false)) return;
        if (event.getTarget() instanceof Player player && afkPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
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

        if (afkPlayers.contains(uuid)) {
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

            if (config.getBoolean("afk.title-enabled", true)) {
                Component title = MiniMessage.miniMessage().deserialize(config.getString("afk.title-text", "<red><bold>YOU ARE AFK</bold></red>"));
                Component subtitle = MiniMessage.miniMessage().deserialize(config.getString("afk.subtitle-text", "<gray>Move to return</gray>"));
                Title.Times times = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(30), Duration.ofMillis(500));
                player.showTitle(Title.title(title, subtitle, times));
            }

            if (config.getBoolean("afk.protection.no-push", false)) {
                player.setCollidable(false);
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

        if (config.getBoolean("afk.protection.no-push", false)) {
            player.setCollidable(true);
        }

        if (config.getBoolean("afk.title-enabled", true)) {
            player.clearTitle();
        }
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message == null ? "" : message);
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

    private String miniMessageToLegacy(String message) {
        return LegacyComponentSerializer.legacySection().serialize(MiniMessage.miniMessage().deserialize(message == null ? "" : message));
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
