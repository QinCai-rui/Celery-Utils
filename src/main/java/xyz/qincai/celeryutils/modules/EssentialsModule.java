package xyz.qincai.celeryutils.modules;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import xyz.qincai.celeryutils.command.CommandRegistrar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;
import xyz.qincai.celeryutils.command.GameModeCommand;
import xyz.qincai.celeryutils.command.KickAllCommand;
import xyz.qincai.celeryutils.command.KillAllCommand;
import xyz.qincai.celeryutils.command.TempBanCommand;
import xyz.qincai.celeryutils.command.TipsCommand;
import xyz.qincai.celeryutils.modules.essentials.ResourcePackManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

public class EssentialsModule implements CeleryModule, Listener {

    private final CeleryUtils plugin;
    private final Random random = new Random();
    private FileConfiguration config;
    private CommandRegistrar registrar;
    private final Map<UUID, BukkitTask> tempbanTasks = new HashMap<>();
    private ResourcePackManager resourcePackManager;

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

        initializeMotd();

        resourcePackManager = new ResourcePackManager(plugin, config);
        resourcePackManager.initialize();

        return true;
    }

    private void registerCommands() {
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

        disableMotd();

        if (resourcePackManager != null) resourcePackManager.disable();
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("modules.essentials.enabled", false);
    }

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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(ServerListPingEvent event) {
        if (!config.getBoolean("motd.enabled", false)) return;
        if (motdComponents.isEmpty()) return;

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
        if (!config.getBoolean("motd.enabled", false)) return;

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
        if (messagesFile == null || messagesFile.isBlank()) messagesFile = "motds.yml";
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
