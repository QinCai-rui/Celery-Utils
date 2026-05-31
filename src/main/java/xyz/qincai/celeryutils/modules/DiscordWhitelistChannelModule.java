package xyz.qincai.celeryutils.modules;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.GsonBuilder;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord Whitelist Channel Module.
 * Listens for messages in a Discord channel and whitelists players on the Minecraft server.
 */
public class DiscordWhitelistChannelModule extends ListenerAdapter implements CeleryModule {

    private static final String CONFIG_PATH = "modules/discord-whitelist-channel/config.yml";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("\\b([a-zA-Z0-9_]{3,16})\\b");

    private final CeleryUtils plugin;
    private JDA jda;
    private boolean enabled = false;
    private File configFile;
    private FileConfiguration moduleConfig;
    
    private long channelId;
    private int maxPlayersPerUser;
    private boolean requiresRole;
    private String requiredRoleId;
    private String uuidType;
    
    private final Map<Long, Integer> userWhitelistCount = new ConcurrentHashMap<>();
    private final Object whitelistFileLock = new Object();

    public DiscordWhitelistChannelModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Discord Whitelist Channel";
    }

    @Override
    public boolean initialize() {
        try {
            configFile = new File(plugin.getDataFolder(), CONFIG_PATH);
            if (!configFile.exists()) {
                plugin.getLogger().warning("Config file not found: " + CONFIG_PATH);
                return false;
            }

            moduleConfig = YamlConfiguration.loadConfiguration(configFile);
            loadSettings(moduleConfig);

            plugin.getDatabaseManager().executeUpdate("CREATE TABLE IF NOT EXISTS discord_whitelist (discord_id BIGINT PRIMARY KEY, count INT)");
            loadWhitelistCounts();

            String token = moduleConfig.getString("bot-token", "").trim();
            if (token.isEmpty()) {
                plugin.getLogger().warning("Discord bot token not configured in " + CONFIG_PATH);
                return false;
            }

            if (channelId == 0L) {
                plugin.getLogger().warning("Discord channel ID not configured in " + CONFIG_PATH);
                return false;
            }

            jda = JDABuilder.createDefault(token)
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.MESSAGE_CONTENT
                    )
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .addEventListeners(this)
                    .build();

            jda.awaitReady();
            enabled = true;
            plugin.getLogger().info("Connected Discord Whitelist Channel module successfully!");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Discord Whitelist Channel module", e);
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
        // JDA shutdown above; this class does not register Bukkit listeners so nothing to unregister
        userWhitelistCount.clear();
    }

    @Override
    public boolean isEnabled() {
        return enabled && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        plugin.getLogger().info("Discord Whitelist Channel bot is ready!");
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!isEnabled() || event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }

        if (event.getChannel().getIdLong() != channelId) {
            return;
        }

        // Check role requirement
        if (requiresRole && event.getMember() != null) {
            if (!event.getMember().getRoles().stream().anyMatch(r -> r.getId().equals(requiredRoleId))) {
                String feedback = "❌ You don't have permission to use this command. Required role: " + requiredRoleId;
                event.getMessage().reply(feedback).queue();
                return;
            }
        }

        Message message = event.getMessage();
        String content = message.getContentRaw().trim();

        // Extract usernames from the message
        Matcher matcher = USERNAME_PATTERN.matcher(content);
        int whitelistedCount = 0;
        StringBuilder results = new StringBuilder();

        while (matcher.find()) {
            String username = matcher.group(1);
            
            // Check if user has reached their whitelist limit
            int currentCount = userWhitelistCount.getOrDefault(event.getAuthor().getIdLong(), 0);
            if (currentCount >= maxPlayersPerUser) {
                results.append("❌ **").append(username).append("** - You've reached your whitelist limit (").append(maxPlayersPerUser).append(" player(s))\n");
                continue;
            }

            // Execute whitelist add command
            String result = executeWhitelistCommand(username);
            
            if (result.contains("Player is already whitelisted")) {
                results.append("⚠️ **").append(username).append("** - Already whitelisted\n");
            } else if (result.contains("Added")) {
                results.append("✅ **").append(username).append("** - Added to whitelist\n");
                whitelistedCount++;
                int updatedCount = currentCount + 1;
                userWhitelistCount.put(event.getAuthor().getIdLong(), updatedCount);
                if (plugin.getDatabaseManager().getType() == xyz.qincai.celeryutils.database.DatabaseType.SQLITE) {
                    plugin.getDatabaseManager().executeUpdate("INSERT OR REPLACE INTO discord_whitelist (discord_id, count) VALUES (" + event.getAuthor().getIdLong() + ", " + updatedCount + ")");
                } else {
                    plugin.getDatabaseManager().executeUpdate("INSERT INTO discord_whitelist (discord_id, count) VALUES (" + event.getAuthor().getIdLong() + ", " + updatedCount + ") ON DUPLICATE KEY UPDATE count=" + updatedCount);
                }
            } else {
                results.append("❌ **").append(username).append("** - Error: ").append(result).append("\n");
            }
        }

        if (results.length() == 0) {
            message.reply("⚠️ No valid usernames found in your message.").queue();
            return;
        }

        // Send feedback reply mentioning the user
        String feedback = event.getAuthor().getAsMention() + "\n" + results.toString();
        message.reply(feedback).queue();

        // Add reaction based on success
        if (whitelistedCount > 0) {
            message.addReaction(Emoji.fromUnicode("✅")).queue();
        } else {
            message.addReaction(Emoji.fromUnicode("⚠️")).queue();
        }
    }

    private String executeWhitelistCommand(String username) {
        try {
            List<org.bukkit.OfflinePlayer> targets = new ArrayList<>();
            
            if ("AUTO".equals(uuidType)) {
                // Retrieve OfflinePlayer on the current JDA thread context.
                // Bukkit.getOfflinePlayer performs a Mojang API lookup which will block.
                // Doing it here off the main thread avoids server lag!
                targets.add(Bukkit.getOfflinePlayer(username));
            } else {
                if ("OFFLINE".equals(uuidType) || "BOTH".equals(uuidType)) {
                    UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
                    targets.add(Bukkit.getOfflinePlayer(offlineUUID));
                }
                
                if ("ONLINE".equals(uuidType) || "BOTH".equals(uuidType)) {
                    UUID onlineUUID = fetchOnlineUUID(username);
                    if (onlineUUID != null) {
                        targets.add(Bukkit.getOfflinePlayer(onlineUUID));
                    } else if ("ONLINE".equals(uuidType)) {
                        return "Could not find a premium Mojang account for " + username;
                    }
                }
            }

            if (targets.isEmpty()) {
                return "Failed to resolve any UUIDs for " + username;
            }

            boolean allWhitelisted = true;
            for (org.bukkit.OfflinePlayer p : targets) {
                if (!p.isWhitelisted()) {
                    allWhitelisted = false;
                    break;
                }
            }

            if (allWhitelisted) {
                ensureWhitelistEntryNames(extractTargetNames(targets, username));
                return "Player is already whitelisted";
            }

            // Execute the actual state change safely on the main thread
            try {
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    for (org.bukkit.OfflinePlayer p : targets) {
                        p.setWhitelisted(true);
                    }
                    ensureWhitelistEntryNames(extractTargetNames(targets, username));
                    return null;
                }).get();
                return "Added " + username + " to whitelist";
            } catch (ExecutionException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to execute whitelist API call", e);
                return "Error executing command";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Command was interrupted";
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to whitelist player: " + username, e);
            return "Error: " + e.getMessage();
        }
    }

    private UUID fetchOnlineUUID(String username) {
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + username);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            if (connection.getResponseCode() == 200) {
                InputStreamReader reader = new InputStreamReader(connection.getInputStream());
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                String id = jsonObject.get("id").getAsString();
                String uuidStr = id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
                return UUID.fromString(uuidStr);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error fetching Mojang UUID for " + username, e);
        }
        return null;
    }

    private Map<UUID, String> extractTargetNames(List<org.bukkit.OfflinePlayer> targets, String fallbackName) {
        Map<UUID, String> uuidToName = new ConcurrentHashMap<>();
        for (org.bukkit.OfflinePlayer player : targets) {
            if (player.getUniqueId() != null) {
                String resolvedName = player.getName();
                uuidToName.put(player.getUniqueId(), (resolvedName == null || resolvedName.isBlank()) ? fallbackName : resolvedName);
            }
        }
        return uuidToName;
    }

    private void ensureWhitelistEntryNames(Map<UUID, String> uuidToName) {
        if (uuidToName.isEmpty()) {
            return;
        }

        try {
            File whitelistFile = new File(Bukkit.getWorldContainer(), "whitelist.json");
            if (!whitelistFile.exists()) {
                return;
            }

            synchronized (whitelistFileLock) {
                JsonElement parsed;
                try (BufferedReader reader = Files.newBufferedReader(whitelistFile.toPath(), StandardCharsets.UTF_8)) {
                    parsed = JsonParser.parseReader(reader);
                }
                if (!parsed.isJsonArray()) return;

                JsonArray entries = parsed.getAsJsonArray();
                boolean updated = false;

                for (JsonElement element : entries) {
                    if (!element.isJsonObject()) {
                        continue;
                    }

                    JsonObject obj = element.getAsJsonObject();
                    if (!obj.has("uuid")) {
                        continue;
                    }

                    UUID entryUuid;
                    try {
                        entryUuid = UUID.fromString(obj.get("uuid").getAsString());
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    String resolvedName = uuidToName.get(entryUuid);
                    if (resolvedName == null || resolvedName.isBlank()) {
                        continue;
                    }

                    JsonElement nameElement = obj.get("name");
                    String currentName = nameElement != null && !nameElement.isJsonNull() ? nameElement.getAsString() : "";
                    if (currentName.isBlank()) {
                        obj.addProperty("name", resolvedName);
                        updated = true;
                    }
                }

                if (updated) {
                    Files.writeString(
                            whitelistFile.toPath(),
                            new GsonBuilder().setPrettyPrinting().create().toJson(entries),
                            StandardCharsets.UTF_8
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update whitelist.json names", e);
        }
    }

    private void loadSettings(FileConfiguration config) {
        this.channelId = config.getLong("channel-id", 0L);
        this.maxPlayersPerUser = config.getInt("max-players-per-user", 1);
        this.requiresRole = config.getBoolean("role-requirement.enabled", false);
        this.requiredRoleId = config.getString("role-requirement.role-id", "");
        this.uuidType = config.getString("uuid-type", "AUTO").toUpperCase();
    }

    private void loadWhitelistCounts() {
        try (java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("SELECT discord_id, count FROM discord_whitelist")) {
             while (rs.next()) {
                 userWhitelistCount.put(rs.getLong("discord_id"), rs.getInt("count"));
             }
             plugin.getLogger().info("Loaded " + userWhitelistCount.size() + " whitelist counts from database.");
        } catch (java.sql.SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load whitelist counts from db", e);
        }
    }
}