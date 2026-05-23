package xyz.qincai.celeryutils.modules;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Discord Link module.
 * Generates a 6-digit code in Minecraft, then accepts it from Discord DM or a configured channel.
 */
public class DiscordLinkModule extends ListenerAdapter implements CeleryModule, Listener {

    private static final String NEW_CONFIG_PATH = "modules/discord-link/config.yml";

    private final CeleryUtils plugin;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, LinkRequest> pendingRequestsByCode = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingCodeByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedAccount> linkedAccountsByUuid = new ConcurrentHashMap<>();

    private JDA jda;
    private boolean enabled = false;
    private File configFile;
    private FileConfiguration moduleConfig;
    private BukkitTask cleanupTask;
    private long guildId;
    private boolean acceptDirectMessages;
    private boolean acceptConfiguredChannel;
    private long configuredChannelId;
    private long codeExpiryMillis;
    private boolean syncOnLink;
    private boolean syncOnJoin;
    private boolean persistLinks;
    private String nicknameFormat;

    public DiscordLinkModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Discord Link";
    }

    @Override
    public boolean initialize() {
        try {
            configFile = resolveConfigFile();
            moduleConfig = YamlConfiguration.loadConfiguration(configFile);
            loadSettings(moduleConfig);
            loadPersistedLinks(moduleConfig);

            String token = moduleConfig.getString("bot-token", "").trim();
            if (token.isEmpty()) {
                plugin.getLogger().warning("Discord bot token not configured in modules/discord-link/config.yml!");
                return false;
            }

            if (guildId == 0L) {
                plugin.getLogger().warning("Discord guild ID not configured in modules/discord-link/config.yml!");
                return false;
            }

            Bukkit.getPluginManager().registerEvents(this, plugin);
            cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpiredRequests, 20L * 30, 20L * 30);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    jda = JDABuilder.createDefault(token)
                            .enableIntents(
                                    GatewayIntent.GUILD_MEMBERS,
                                    GatewayIntent.GUILD_MESSAGES,
                                    GatewayIntent.DIRECT_MESSAGES,
                                    GatewayIntent.MESSAGE_CONTENT
                            )
                            .addEventListeners(this)
                            .build();

                    jda.awaitReady();
                    enabled = true;
                    plugin.getLogger().info("Connected Discord Link module successfully!");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to initialize Discord Link bot", e);
                }
            });

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Discord Link module config", e);
            return false;
        }
    }

    @Override
    public void disable() {
        enabled = false;
        if (jda != null) {
            try {
                jda.shutdownNow();
                jda.awaitStatus(JDA.Status.SHUTDOWN);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Ignore
            }
        }
        // Cancel scheduled cleanup task
        try {
            if (cleanupTask != null) {
                cleanupTask.cancel();
                cleanupTask = null;
            }
        } catch (Exception ignored) {}

        // Unregister any Bukkit event listeners and clear memory
        try {
            HandlerList.unregisterAll(this);
        } catch (Exception ignored) {}

        pendingRequestsByCode.clear();
        pendingCodeByPlayer.clear();
        linkedAccountsByUuid.clear();
    }

    @Override
    public boolean isEnabled() {
        return enabled && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        plugin.getLogger().info("Discord Link bot is ready!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!isEnabled() || !syncOnJoin) {
            return;
        }

        syncPlayerNickname(event.getPlayer());
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!isEnabled() || event.getAuthor().isBot()) {
            return;
        }

        if (!isAcceptedChannel(event)) {
            return;
        }

        String code = extractLinkCode(event.getMessage().getContentRaw());
        if (code == null) {
            return;
        }

        handleLinkCode(event, code);
    }

    /**
     * Starts a Discord link session for a player and sends the code in-game.
     */
    public boolean startLinkSession(Player player) {
        if (!isEnabled()) {
            player.sendMessage("§cDiscord Link is not available right now.");
            return false;
        }

        long linkedDiscordId = getLinkedDiscordId(player.getUniqueId(), player.getName());
        if (linkedDiscordId != 0L) {
            player.sendMessage("§eThis Minecraft account is already linked. Running this command again will relink it.");
        }

        cleanupExpiredRequests();

        String existingCode = pendingCodeByPlayer.get(player.getUniqueId());
        if (existingCode != null) {
            LinkRequest existingRequest = pendingRequestsByCode.get(existingCode);
            if (existingRequest != null && !existingRequest.isExpired()) {
                sendLinkCodeToPlayer(player, existingCode, existingRequest.expiresAtMillis());
                return true;
            }

            pendingCodeByPlayer.remove(player.getUniqueId());
            pendingRequestsByCode.remove(existingCode);
        }

        String newCode = generateUniqueCode();
        long expiresAtMillis = System.currentTimeMillis() + codeExpiryMillis;
        LinkRequest request = new LinkRequest(player.getUniqueId(), player.getName(), expiresAtMillis);
        pendingRequestsByCode.put(newCode, request);
        pendingCodeByPlayer.put(player.getUniqueId(), newCode);

        sendLinkCodeToPlayer(player, newCode, expiresAtMillis);
        return true;
    }

    /**
     * Syncs a player's nickname to Discord.
     */
    public void syncPlayerNickname(Player player) {
        if (!isEnabled()) {
            return;
        }

        try {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                plugin.getLogger().warning("Guild not found: " + guildId);
                return;
            }

            long discordId = getLinkedDiscordId(player.getUniqueId(), player.getName());
            if (discordId == 0L) {
                return;
            }

            String targetNickname = formatNickname(player.getName());
            guild.retrieveMemberById(discordId).queue(member -> {
                if (member == null || targetNickname.equals(member.getEffectiveName())) {
                    return;
                }

                member.modifyNickname(targetNickname).queue(
                        unused -> plugin.getLogger().info("Synced " + player.getName() + " to Discord nickname"),
                        failure -> plugin.getLogger().log(Level.WARNING,
                                "Failed to update nickname for " + player.getName(), failure)
                );
            }, failure -> plugin.getLogger().log(Level.WARNING,
                    "Failed to resolve Discord member for " + player.getName(), failure));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to sync player nickname: " + player.getName(), e);
        }
    }

    public JDA getJDA() {
        return jda;
    }

    private File resolveConfigFile() {
        return new File(plugin.getDataFolder(), NEW_CONFIG_PATH);
    }

    private void loadSettings(FileConfiguration cfg) {
        guildId = cfg.getLong("guild-id", 0L);
        acceptDirectMessages = cfg.getBoolean("linking.accept-dm", true);
        acceptConfiguredChannel = cfg.getBoolean("linking.accept-channel", true);
        configuredChannelId = cfg.getLong("linking.channel-id", 0L);
        codeExpiryMillis = Math.max(60L, cfg.getLong("linking.code-expiry-seconds", 600L)) * 1000L;
        syncOnLink = cfg.getBoolean("linking.sync-on-link", true);
        syncOnJoin = cfg.getBoolean("linking.sync-on-join", true);
        persistLinks = cfg.getBoolean("linking.persist-links", true);
        nicknameFormat = cfg.getString("linking.nickname-format", "%minecraft_name%");
    }

    private void loadPersistedLinks(FileConfiguration cfg) {
        linkedAccountsByUuid.clear();

        ConfigurationSection links = cfg.getConfigurationSection("links");
        if (links != null) {
            for (String key : links.getKeys(false)) {
                try {
                    long discordId = Long.parseLong(key);
                    String uuidText = links.getString(key + ".minecraft-uuid", "");
                    String minecraftName = links.getString(key + ".minecraft-name", "");
                    if (uuidText.isBlank()) {
                        continue;
                    }

                    UUID minecraftUuid = UUID.fromString(uuidText);
                    linkedAccountsByUuid.put(minecraftUuid, new LinkedAccount(discordId, minecraftUuid, minecraftName));
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping invalid Discord link entry: " + key);
                }
            }
        }
    }

    private void handleLinkCode(MessageReceivedEvent event, String code) {
        LinkRequest request = pendingRequestsByCode.remove(code);
        if (request == null) {
            event.getChannel().sendMessage(getMessage("messages.discord-code-invalid",
                    "§cThat link code is invalid or unknown.")).queue();
            return;
        }

        pendingCodeByPlayer.remove(request.playerUuid());

        if (request.isExpired()) {
            event.getChannel().sendMessage(getMessage("messages.discord-code-expired",
                    "§cThat link code has expired. Ask the player to run /celeryutils link again.")).queue();
            notifyPlayerExpired(request.playerUuid());
            return;
        }

        long discordId = event.getAuthor().getIdLong();
        linkedAccountsByUuid.put(request.playerUuid(),
                new LinkedAccount(discordId, request.playerUuid(), request.playerName()));

        saveLinkToConfig(discordId, request.playerUuid(), request.playerName());

        String successMessage = getMessage("messages.discord-linked",
                "Linked Minecraft account %minecraft_name%.");
        event.getChannel().sendMessage(applyPlaceholders(successMessage, request.playerName(), code, discordId, 0L)).queue();

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(request.playerUuid());
            if (player != null) {
                player.sendMessage(getMessage("messages.player-linked",
                        "§aYour Minecraft account is now linked to Discord."));
                if (syncOnLink) {
                    syncPlayerNickname(player);
                }
            }
        });
    }

    private void saveLinkToConfig(long discordId, UUID minecraftUuid, String minecraftName) {
        if (!persistLinks || moduleConfig == null || configFile == null) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (moduleConfig) {
                moduleConfig.set("links." + discordId + ".minecraft-uuid", minecraftUuid.toString());
                moduleConfig.set("links." + discordId + ".minecraft-name", minecraftName);
                moduleConfig.set("links." + discordId + ".linked-at", System.currentTimeMillis());
        
                try {
                    moduleConfig.save(configFile);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save Discord link config", e);
                }
            }
        });
    }

    private void notifyPlayerExpired(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(getMessage("messages.player-code-expired",
                "§cYour Discord link code expired. Run §e/celeryutils link §cto get a new one.")));
    }

    private void sendLinkCodeToPlayer(Player player, String code, long expiresAtMillis) {
        long minutes = Math.max(1L, (expiresAtMillis - System.currentTimeMillis()) / 60000L);
        String message = getMessage("messages.player-code-issued",
                "§aDiscord link code: §e%code% §7(Expires in %minutes% minute(s)). Send it to the Discord bot in DM or the configured channel.");
        player.sendMessage(applyPlaceholders(message, player.getName(), code, 0L, minutes));
    }

    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        pendingRequestsByCode.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAtMillis() <= now) {
                pendingCodeByPlayer.remove(entry.getValue().playerUuid());
                notifyPlayerExpired(entry.getValue().playerUuid());
                return true;
            }
            return false;
        });
    }

    private boolean isAcceptedChannel(MessageReceivedEvent event) {
        if (event.isFromGuild()) {
            if (!acceptConfiguredChannel || configuredChannelId == 0L) {
                return false;
            }
            long channelId = event.getChannel().getIdLong();
            if (channelId == configuredChannelId) {
                return true;
            }
            if (event.getChannelType().isThread()) {
                net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel thread = event.getChannel().asThreadChannel();
                return thread != null && thread.getParentChannel().getIdLong() == configuredChannelId;
            }
            return false;
        }

        return acceptDirectMessages;
    }

    private String extractLinkCode(String contentRaw) {
        if (contentRaw == null) {
            return null;
        }

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{6})").matcher(contentRaw);
        if (m.find()) {
            return m.group(1);
        }

        return null;
    }

    private String generateUniqueCode() {
        String code;
        do {
            code = String.format("%06d", secureRandom.nextInt(1_000_000));
        } while (pendingRequestsByCode.containsKey(code));
        return code;
    }

    private long getLinkedDiscordId(UUID minecraftUuid, String playerName) {
        LinkedAccount account = linkedAccountsByUuid.get(minecraftUuid);
        if (account != null) {
            return account.discordId();
        }

        return 0L;
    }

    private String formatNickname(String minecraftName) {
        String formatted = nicknameFormat == null || nicknameFormat.isBlank() ? "%minecraft_name%" : nicknameFormat;
        return formatted.replace("%minecraft_name%", minecraftName)
                .replace("%player_name%", minecraftName);
    }

    private String getMessage(String path, String fallback) {
        if (moduleConfig == null) {
            return fallback;
        }

        return moduleConfig.getString(path, fallback);
    }

    private String applyPlaceholders(String message, String minecraftName, String code, long discordId, long minutes) {
        return message.replace("%minecraft_name%", minecraftName)
                .replace("%player_name%", minecraftName)
                .replace("%code%", code)
                .replace("%discord_id%", Long.toString(discordId))
                .replace("%minutes%", Long.toString(minutes));
    }

    private record LinkRequest(UUID playerUuid, String playerName, long expiresAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    private record LinkedAccount(long discordId, UUID minecraftUuid, String minecraftName) {
    }
}