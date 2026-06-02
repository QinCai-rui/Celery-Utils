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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.scheduler.BukkitRunnable;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;

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
 * Also listens for player joins to update whitelist.json with proper names and clean up duplicates.
 */
public class DiscordWhitelistChannelModule extends ListenerAdapter implements CeleryModule, Listener {

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
    
    // Track recently processed players to avoid duplicate processing
    private final Set<UUID> recentlyProcessedPlayers = ConcurrentHashMap.newKeySet();

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

            // Register Bukkit listener for player login events
            Bukkit.getPluginManager().registerEvents(this, plugin);

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
        
        // Unregister Bukkit listeners
        try {
            org.bukkit.event.HandlerList.unregisterAll((Listener) this);
        } catch (Exception e) {
            // Ignore
        }
        
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
        userWhitelistCount.clear();
        recentlyProcessedPlayers.clear();
    }

    @Override
    public boolean isEnabled() {
        return enabled && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        plugin.getLogger().info("Discord Whitelist Channel bot is ready!");
    }

    /**
     * Listen for player login events to update whitelist.json with player names
     * and clean up duplicate UUID entries.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!isEnabled()) {
            return;
        }

        // Don't process players who weren't allowed to join - this would
        // incorrectly add them via updateWhitelistEntryName below.
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID playerUUID = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        
        // Skip if we've recently processed this player to avoid duplicate work
        if (!recentlyProcessedPlayers.add(playerUUID)) {
            return;
        }
        
        // Process asynchronously to avoid blocking the login thread
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    updateWhitelistEntryName(playerUUID, playerName);
                    updatePremiumUsernameAndRemoveOffline(playerUUID, playerName);
                    removePremiumCounterpartForCrackedPlayer(playerUUID, playerName);
                    // Clean up after a delay to allow for re-login
                    Bukkit.getScheduler().runTaskLater(plugin, () -> recentlyProcessedPlayers.remove(playerUUID), 1200L); // 1 minute
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to update whitelist entry for " + playerName, e);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Updates the whitelist.json file with the player's name and removes duplicate entries.
     */
    private void updateWhitelistEntryName(UUID playerUUID, String playerName) {
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
                
                // Build maps to detect duplicates
                Map<UUID, JsonObject> uuidToEntry = new LinkedHashMap<>();
                Map<String, UUID> nameToUuid = new HashMap<>();
                Set<UUID> uuidsToRemove = new HashSet<>();
                
                // First pass: collect all entries and detect duplicates
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

                    String entryName = obj.has("name") && !obj.get("name").isJsonNull() 
                        ? obj.get("name").getAsString().trim() 
                        : "";
                    
                    // Check if this is our target player
                    if (entryUuid.equals(playerUUID)) {
                        obj.addProperty("name", playerName);
                        updated = true;
                        plugin.getLogger().info("Updated whitelist entry for " + playerName + " (" + playerUUID + ")");
                    }
                    
                    // If this entry has a name, check for duplicates
                    if (!entryName.isEmpty()) {
                        if (nameToUuid.containsKey(entryName.toLowerCase())) {
                            // Found a duplicate! Keep the one with the matching UUID, or the first one
                            UUID existingUuid = nameToUuid.get(entryName.toLowerCase());
                            if (!existingUuid.equals(entryUuid)) {
                                // Mark the one we want to remove (prefer keeping the one that matches our player)
                                if (entryUuid.equals(playerUUID)) {
                                    uuidsToRemove.add(existingUuid);
                                    nameToUuid.put(entryName.toLowerCase(), entryUuid);
                                } else {
                                    uuidsToRemove.add(entryUuid);
                                }
                            }
                        } else {
                            nameToUuid.put(entryName.toLowerCase(), entryUuid);
                        }
                    }
                    
                    uuidToEntry.put(entryUuid, obj);
                }

                // If player not found, add them
                if (!uuidToEntry.containsKey(playerUUID)) {
                    JsonObject newEntry = new JsonObject();
                    newEntry.addProperty("uuid", playerUUID.toString());
                    newEntry.addProperty("name", playerName);
                    uuidToEntry.put(playerUUID, newEntry);
                    updated = true;
                    plugin.getLogger().info("Added missing whitelist entry for " + playerName + " (" + playerUUID + ")");
                }

                // Build cleaned array, removing duplicates
                if (!uuidsToRemove.isEmpty()) {
                    updated = true;
                    for (UUID uuid : uuidsToRemove) {
                        JsonObject removed = uuidToEntry.remove(uuid);
                        String removedName = removed != null && removed.has("name") ? removed.get("name").getAsString() : "unknown";
                        plugin.getLogger().info("Removed duplicate whitelist entry for " + removedName + " (" + uuid + ")");
                    }
                }

                if (updated) {
                    JsonArray cleanedEntries = new JsonArray();
                    for (JsonObject entry : uuidToEntry.values()) {
                        cleanedEntries.add(entry);
                    }
                    
                    Files.writeString(
                            whitelistFile.toPath(),
                            new GsonBuilder().setPrettyPrinting().create().toJson(cleanedEntries),
                            StandardCharsets.UTF_8
                    );
                    
                    // Reload the whitelist
                    Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist reload");
                            plugin.getLogger().info("Reloaded whitelist after cleaning up entries for " + playerName);
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Failed to reload whitelist", e);
                        }
                        return null;
                    });
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update whitelist.json for " + playerName, e);
        }
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

    private String fetchOnlineUsername(UUID uuid) {
        try {
            // because this api needs UUID without dashes
            String uuidStr = uuid.toString().replace("-", "");
            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + uuidStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (connection.getResponseCode() == 200) {
                InputStreamReader reader = new InputStreamReader(connection.getInputStream());
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                String name = jsonObject.get("name").getAsString();
                return name != null ? name.trim() : null;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error fetching Mojang username for UUID " + uuid, e);
        }
        return null;
    }

    private void updatePremiumUsernameAndRemoveOffline(UUID playerUUID, String playerName) {
        try {
            String premiumUsername = fetchOnlineUsername(playerUUID);
            if (premiumUsername == null || premiumUsername.isBlank()) {
                return;
            }

            UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + premiumUsername).getBytes(StandardCharsets.UTF_8));

            synchronized (whitelistFileLock) {
                File whitelistFile = new File(Bukkit.getWorldContainer(), "whitelist.json");
                if (!whitelistFile.exists()) {
                    return;
                }

                JsonElement parsed;
                try (BufferedReader reader = Files.newBufferedReader(whitelistFile.toPath(), StandardCharsets.UTF_8)) {
                    parsed = JsonParser.parseReader(reader);
                }
                if (!parsed.isJsonArray()) return;

                JsonArray entries = parsed.getAsJsonArray();
                boolean updated = false;

                JsonArray cleanedEntries = new JsonArray();
                for (JsonElement element : entries) {
                    if (!element.isJsonObject()) {
                        cleanedEntries.add(element);
                        continue;
                    }

                    JsonObject obj = element.getAsJsonObject();
                    if (!obj.has("uuid")) {
                        cleanedEntries.add(element);
                        continue;
                    }

                    UUID entryUuid;
                    try {
                        entryUuid = UUID.fromString(obj.get("uuid").getAsString());
                    } catch (IllegalArgumentException ignored) {
                        cleanedEntries.add(element);
                        continue;
                    }

                    if (entryUuid.equals(offlineUUID)) {
                        plugin.getLogger().info("Removed offline whitelist entry for " + premiumUsername + " (" + offlineUUID + ")");
                        updated = true;
                        continue;
                    }

                    if (entryUuid.equals(playerUUID)) {
                        String currentName = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : "";
                        if (!premiumUsername.equals(currentName)) {
                            obj.addProperty("name", premiumUsername);
                            plugin.getLogger().info("Updated premium whitelist entry name to " + premiumUsername + " for (" + playerUUID + ")");
                            updated = true;
                        }
                    }

                    cleanedEntries.add(obj);
                }

                if (updated) {
                    Files.writeString(
                            whitelistFile.toPath(),
                            new GsonBuilder().setPrettyPrinting().create().toJson(cleanedEntries),
                            StandardCharsets.UTF_8
                    );

                    Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist reload");
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Failed to reload whitelist", e);
                        }
                        return null;
                    });
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update premium whitelist entry for " + playerName, e);
        }
    }

    private void removePremiumCounterpartForCrackedPlayer(UUID playerUUID, String playerName) {
        UUID computedOfflineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
        if (!playerUUID.equals(computedOfflineUUID)) {
            return;
        }

        UUID onlineUUID = fetchOnlineUUID(playerName);
        if (onlineUUID == null) {
            return;
        }

        synchronized (whitelistFileLock) {
            try {
                File whitelistFile = new File(Bukkit.getWorldContainer(), "whitelist.json");
                if (!whitelistFile.exists()) {
                    return;
                }

                JsonElement parsed;
                try (BufferedReader reader = Files.newBufferedReader(whitelistFile.toPath(), StandardCharsets.UTF_8)) {
                    parsed = JsonParser.parseReader(reader);
                }
                if (!parsed.isJsonArray()) return;

                JsonArray entries = parsed.getAsJsonArray();
                boolean removed = false;

                JsonArray cleanedEntries = new JsonArray();
                for (JsonElement element : entries) {
                    if (!element.isJsonObject()) {
                        cleanedEntries.add(element);
                        continue;
                    }

                    JsonObject obj = element.getAsJsonObject();
                    if (!obj.has("uuid")) {
                        cleanedEntries.add(element);
                        continue;
                    }

                    UUID entryUuid;
                    try {
                        entryUuid = UUID.fromString(obj.get("uuid").getAsString());
                    } catch (IllegalArgumentException ignored) {
                        cleanedEntries.add(element);
                        continue;
                    }

                    if (entryUuid.equals(onlineUUID)) {
                        plugin.getLogger().info("Removed premium whitelist counterpart for cracked player " + playerName + " (" + onlineUUID + ")");
                        removed = true;
                        continue;
                    }

                    cleanedEntries.add(obj);
                }

                if (removed) {
                    Files.writeString(
                            whitelistFile.toPath(),
                            new GsonBuilder().setPrettyPrinting().create().toJson(cleanedEntries),
                            StandardCharsets.UTF_8
                    );

                    Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist reload");
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Failed to reload whitelist", e);
                        }
                        return null;
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to remove premium counterpart for cracked player " + playerName, e);
            }
        }
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