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
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    
    private final Map<Long, Integer> userWhitelistCount = new ConcurrentHashMap<>();

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
                userWhitelistCount.put(event.getAuthor().getIdLong(), currentCount + 1);
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
            // Retrieve OfflinePlayer on the current JDA thread context.
            // Bukkit.getOfflinePlayer performs a Mojang API lookup which will block.
            // Doing it here off the main thread avoids server lag!
            org.bukkit.OfflinePlayer player = Bukkit.getOfflinePlayer(username);

            if (player.isWhitelisted()) {
                return "Player is already whitelisted";
            }

            // Execute the actual state change safely on the main thread
            try {
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    player.setWhitelisted(true);
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

    private void loadSettings(FileConfiguration config) {
        this.channelId = config.getLong("channel-id", 0L);
        this.maxPlayersPerUser = config.getInt("max-players-per-user", 1);
        this.requiresRole = config.getBoolean("role-requirement.enabled", false);
        this.requiredRoleId = config.getString("role-requirement.role-id", "");
    }
}
