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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private boolean adminEnabled;
    private String adminRoleId;
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
            plugin.getDatabaseManager().executeUpdate("CREATE TABLE IF NOT EXISTS whitelist_entry_names (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL)");
            plugin.getDatabaseManager().executeUpdate("CREATE TABLE IF NOT EXISTS whitelist_owners (uuid VARCHAR(36) PRIMARY KEY, discord_id BIGINT NOT NULL)");
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
        
        // Skip if already cleaned in database - avoids unnecessary
        // whitelist.json parsing and whitelist reload on every join
        if (isWhitelistEntryCleaned(playerUUID, playerName)) {
            return;
        }
        
        // Skip if we've recently processed this player to avoid duplicate work
        if (!recentlyProcessedPlayers.add(playerUUID)) {
            return;
        }
        
        // Process asynchronously to avoid blocking the login thread
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    cleanWhitelistForPlayer(playerUUID, playerName);
                    markWhitelistEntryCleaned(playerUUID, playerName);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to update whitelist entry for " + playerName, e);
                } finally {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> recentlyProcessedPlayers.remove(playerUUID), 1200L); // 1 minute
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void cleanWhitelistForPlayer(UUID playerUUID, String playerName) {
        boolean isOfflinePlayer = playerUUID.equals(UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)));
        String premiumUsername = fetchOnlineUsername(playerUUID);
        UUID offlineUUID = (premiumUsername != null && !premiumUsername.isBlank())
                ? UUID.nameUUIDFromBytes(("OfflinePlayer:" + premiumUsername).getBytes(StandardCharsets.UTF_8))
                : null;
        UUID onlineUUID = isOfflinePlayer ? fetchOnlineUUID(playerName) : null;

        synchronized (whitelistFileLock) {
            try {
                File whitelistFile = new File(Bukkit.getWorldContainer(), "whitelist.json");
                if (!whitelistFile.exists()) return;

                JsonElement parsed;
                try (BufferedReader reader = Files.newBufferedReader(whitelistFile.toPath(), StandardCharsets.UTF_8)) {
                    parsed = JsonParser.parseReader(reader);
                }
                if (!parsed.isJsonArray()) return;

                JsonArray entries = parsed.getAsJsonArray();
                JsonArray cleanedEntries = new JsonArray();
                Map<UUID, JsonObject> uuidToEntry = new LinkedHashMap<>();
                Map<String, UUID> nameToUuid = new HashMap<>();
                Set<UUID> uuidsToRemove = new HashSet<>();
                boolean updated = false;

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

                    // Remove cracked/offline counterpart for premium player
                    if (offlineUUID != null && entryUuid.equals(offlineUUID)) {
                        plugin.getLogger().info("Removed offline whitelist entry for " + premiumUsername + " (" + offlineUUID + ")");
                        uuidsToRemove.add(offlineUUID);
                        continue;
                    }

                    // Remove premium counterpart for cracked/offline player
                    if (onlineUUID != null && entryUuid.equals(onlineUUID)) {
                        plugin.getLogger().info("Removed premium whitelist counterpart for cracked player " + playerName + " (" + onlineUUID + ")");
                        uuidsToRemove.add(onlineUUID);
                        continue;
                    }

                    // Update name for matching entry
                    if (entryUuid.equals(playerUUID)) {
                        String currentName = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : "";
                        String targetName = (premiumUsername != null && !premiumUsername.isBlank()) ? premiumUsername : playerName;
                        if (!targetName.equals(currentName)) {
                            obj.addProperty("name", targetName);
                            updated = true;
                            plugin.getLogger().info("Updated whitelist entry name to " + targetName + " for (" + playerUUID + ")");
                        }
                    }

                    // Detect duplicate usernames
                    String entryName = obj.has("name") && !obj.get("name").isJsonNull()
                            ? obj.get("name").getAsString().trim()
                            : "";
                    if (!entryName.isEmpty()) {
                        String lowerName = entryName.toLowerCase();
                        if (nameToUuid.containsKey(lowerName)) {
                            UUID existingUuid = nameToUuid.get(lowerName);
                            if (!existingUuid.equals(entryUuid)) {
                                if (entryUuid.equals(playerUUID)) {
                                    uuidsToRemove.add(existingUuid);
                                    nameToUuid.put(lowerName, entryUuid);
                                } else {
                                    uuidsToRemove.add(entryUuid);
                                }
                            }
                        } else {
                            nameToUuid.put(lowerName, entryUuid);
                        }
                    }

                    uuidToEntry.put(entryUuid, obj);
                }

                // Add player if missing
                if (!uuidToEntry.containsKey(playerUUID)) {
                    String targetName = (premiumUsername != null && !premiumUsername.isBlank()) ? premiumUsername : playerName;
                    JsonObject newEntry = new JsonObject();
                    newEntry.addProperty("uuid", playerUUID.toString());
                    newEntry.addProperty("name", targetName);
                    uuidToEntry.put(playerUUID, newEntry);
                    updated = true;
                    plugin.getLogger().info("Added missing whitelist entry for " + targetName + " (" + playerUUID + ")");
                }

                // Remove dupes
                for (UUID uuid : uuidsToRemove) {
                    JsonObject removed = uuidToEntry.remove(uuid);
                    String removedName = removed != null && removed.has("name") ? removed.get("name").getAsString() : "unknown";
                    plugin.getLogger().info("Removed whitelist entry for " + removedName + " (" + uuid + ")");
                    updated = true;
                }

                // Build output only from non-excluded entries
                if (updated || !uuidsToRemove.isEmpty()) {
                    cleanedEntries = new JsonArray();
                    for (JsonObject entry : uuidToEntry.values()) {
                        cleanedEntries.add(entry);
                    }
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
                plugin.getLogger().log(Level.WARNING, "Failed to clean whitelist for " + playerName, e);
            }
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

        // Check for unwhitelist command
        String lowerContent = content.toLowerCase();
        if (lowerContent.equals("!unwhitelist") || lowerContent.equals("!remove")) {
            message.reply("Usage: `!unwhitelist <username>` or `!remove <username>`").queue();
            return;
        }
        if (lowerContent.startsWith("!unwhitelist ") || lowerContent.startsWith("!remove ")) {
            String username = content.substring(content.indexOf(' ') + 1).trim();
            if (USERNAME_PATTERN.matcher(username).matches()) {
                handleUnwhitelist(event, username);
            } else {
                message.reply("❌ Invalid Minecraft username: `" + username + "`").queue();
            }
            return;
        }

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
                long discordId = event.getAuthor().getIdLong();
                int updatedCount = currentCount + 1;
                userWhitelistCount.put(discordId, updatedCount);
                if (plugin.getDatabaseManager().getType() == xyz.qincai.celeryutils.database.DatabaseType.SQLITE) {
                    plugin.getDatabaseManager().executeUpdate("INSERT OR REPLACE INTO discord_whitelist (discord_id, count) VALUES (?, ?)", discordId, updatedCount);
                } else {
                    plugin.getDatabaseManager().executeUpdate("INSERT INTO discord_whitelist (discord_id, count) VALUES (?, ?) ON DUPLICATE KEY UPDATE count=?", discordId, updatedCount, updatedCount);
                }
                Set<UUID> uuids = resolveUuids(username);
                if (!uuids.isEmpty()) {
                    storeOwnership(uuids, discordId);
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

    private void handleUnwhitelist(MessageReceivedEvent event, String username) {
        Message message = event.getMessage();
        long discordId = event.getAuthor().getIdLong();

        // Check ownership: user must own all resolved UUIDs or have the required role
        Set<UUID> uuids = resolveUuids(username);
        if (uuids.isEmpty()) {
            message.reply("❌ **" + username + "** - Failed to resolve any UUIDs.").queue();
            return;
        }

        boolean isAdmin = false;
        if (event.getMember() != null) {
            if (adminEnabled && event.getMember().getRoles().stream().anyMatch(r -> r.getId().equals(adminRoleId))) {
                isAdmin = true;
            }
        }
        if (!isAdmin) {
            for (UUID uuid : uuids) {
                if (!isOwnedBy(uuid, discordId)) {
                    message.reply("❌ **" + username + "** - You did not whitelist this player.").queue();
                    message.addReaction(Emoji.fromUnicode("❌")).queue();
                    return;
                }
            }
        }

        String result = executeUnwhitelistCommand(uuids);

        if (result.contains("Removed")) {
            int currentCount = userWhitelistCount.getOrDefault(discordId, 0);
            if (currentCount > 0) {
                int updatedCount = currentCount - 1;
                userWhitelistCount.put(discordId, updatedCount);
                if (plugin.getDatabaseManager().getType() == xyz.qincai.celeryutils.database.DatabaseType.SQLITE) {
                    plugin.getDatabaseManager().executeUpdate("INSERT OR REPLACE INTO discord_whitelist (discord_id, count) VALUES (?, ?)", discordId, updatedCount);
                } else {
                    plugin.getDatabaseManager().executeUpdate("INSERT INTO discord_whitelist (discord_id, count) VALUES (?, ?) ON DUPLICATE KEY UPDATE count=?", discordId, updatedCount, updatedCount);
                }
            }
            message.reply("✅ **" + username + "** - " + result).queue();
            message.addReaction(Emoji.fromUnicode("✅")).queue();
        } else if (result.contains("not whitelisted")) {
            message.reply("⚠️ **" + username + "** - " + result).queue();
            message.addReaction(Emoji.fromUnicode("⚠️")).queue();
        } else {
            message.reply("❌ **" + username + "** - " + result).queue();
            message.addReaction(Emoji.fromUnicode("❌")).queue();
        }
    }

    private String executeUnwhitelistCommand(Set<UUID> uuids) {
        try {
            boolean removed = removeWhitelistEntries(uuids);
            if (!removed) {
                return "not whitelisted";
            }
            return "Removed from whitelist";
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to unwhitelist player", e);
            return "Error: " + e.getMessage();
        }
    }

    private boolean removeWhitelistEntries(Set<UUID> uuids) {
        synchronized (whitelistFileLock) {
            try {
                File whitelistFile = new File(Bukkit.getWorldContainer(), "whitelist.json");
                if (!whitelistFile.exists()) return false;

                JsonElement parsed;
                try (BufferedReader reader = Files.newBufferedReader(whitelistFile.toPath(), StandardCharsets.UTF_8)) {
                    parsed = JsonParser.parseReader(reader);
                }
                if (!parsed.isJsonArray()) return false;

                JsonArray entries = parsed.getAsJsonArray();
                JsonArray cleaned = new JsonArray();
                boolean anyRemoved = false;

                for (JsonElement element : entries) {
                    if (!element.isJsonObject()) {
                        cleaned.add(element);
                        continue;
                    }
                    JsonObject obj = element.getAsJsonObject();
                    if (!obj.has("uuid")) {
                        cleaned.add(element);
                        continue;
                    }
                    UUID entryUuid;
                    try {
                        entryUuid = UUID.fromString(obj.get("uuid").getAsString());
                    } catch (IllegalArgumentException ignored) {
                        cleaned.add(element);
                        continue;
                    }
                    if (uuids.contains(entryUuid)) {
                        anyRemoved = true;
                        continue;
                    }
                    cleaned.add(obj);
                }

                if (anyRemoved) {
                    Files.writeString(
                            whitelistFile.toPath(),
                            new GsonBuilder().setPrettyPrinting().create().toJson(cleaned),
                            StandardCharsets.UTF_8
                    );
                    Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist reload");
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Failed to reload whitelist", e);
                        }
                        return null;
                    }).get();
                }

                return anyRemoved;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to remove whitelist entries", e);
                return false;
            }
        }
    }

    private Set<UUID> resolveUuids(String username) {
        Set<UUID> uuids = new HashSet<>();
        if ("AUTO".equals(uuidType)) {
            uuids.add(Bukkit.getOfflinePlayer(username).getUniqueId());
        } else {
            if ("OFFLINE".equals(uuidType) || "BOTH".equals(uuidType)) {
                uuids.add(UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)));
            }
            if ("ONLINE".equals(uuidType) || "BOTH".equals(uuidType)) {
                UUID onlineUUID = fetchOnlineUUID(username);
                if (onlineUUID != null) {
                    uuids.add(onlineUUID);
                }
            }
        }
        return uuids;
    }

    private void storeOwnership(Set<UUID> uuids, long discordId) {
        for (UUID uuid : uuids) {
            if (plugin.getDatabaseManager().getType() == xyz.qincai.celeryutils.database.DatabaseType.SQLITE) {
                plugin.getDatabaseManager().executeUpdate("INSERT OR REPLACE INTO whitelist_owners (uuid, discord_id) VALUES (?, ?)", uuid.toString(), discordId);
            } else {
                plugin.getDatabaseManager().executeUpdate("INSERT INTO whitelist_owners (uuid, discord_id) VALUES (?, ?) ON DUPLICATE KEY UPDATE discord_id=?", uuid.toString(), discordId, discordId);
            }
        }
    }

    private boolean isOwnedBy(UUID uuid, long discordId) {
        try (java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT discord_id FROM whitelist_owners WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getLong("discord_id") == discordId;
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check whitelist ownership for " + uuid, e);
        }
        return false;
    }

    private String executeWhitelistCommand(String username) {
        try {
            Set<UUID> uuids = resolveUuids(username);
            if (uuids.isEmpty()) {
                return "Failed to resolve any UUIDs for " + username;
            }

            List<org.bukkit.OfflinePlayer> targets = new ArrayList<>();
            for (UUID uuid : uuids) {
                targets.add(Bukkit.getOfflinePlayer(uuid));
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
                // Clear any existing cleanup entries so first join triggers a fresh cleanup
                for (org.bukkit.OfflinePlayer p : targets) {
                    UUID targetUuid = p.getUniqueId();
                    if (targetUuid != null) {
                        removeWhitelistEntryCleaned(targetUuid);
                    }
                }
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

    private boolean isWhitelistEntryCleaned(UUID uuid, String name) {
        try (java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT name FROM whitelist_entry_names WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return name.equals(rs.getString("name"));
                }
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check whitelist cleanup status for " + uuid, e);
        }
        return false;
    }

    private void markWhitelistEntryCleaned(UUID uuid, String name) {
        if (plugin.getDatabaseManager().getType() == xyz.qincai.celeryutils.database.DatabaseType.SQLITE) {
            plugin.getDatabaseManager().executeUpdate("INSERT OR REPLACE INTO whitelist_entry_names (uuid, name) VALUES (?, ?)", uuid.toString(), name);
        } else {
            plugin.getDatabaseManager().executeUpdate("INSERT INTO whitelist_entry_names (uuid, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name=?", uuid.toString(), name, name);
        }
    }

    private void removeWhitelistEntryCleaned(UUID uuid) {
        plugin.getDatabaseManager().executeUpdate("DELETE FROM whitelist_entry_names WHERE uuid = ?", uuid.toString());
    }

    private void loadSettings(FileConfiguration config) {
        this.channelId = config.getLong("channel-id", 0L);
        this.maxPlayersPerUser = config.getInt("max-players-per-user", 1);
        this.requiresRole = config.getBoolean("role-requirement.enabled", false);
        this.requiredRoleId = config.getString("role-requirement.role-id", "");
        this.adminEnabled = config.getBoolean("admin.enabled", false);
        this.adminRoleId = config.getString("admin.role-id", "");
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