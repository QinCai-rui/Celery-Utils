package xyz.qincai.celeryutils.modules;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Discord-Minecraft Username Sync Module
 * Syncs Minecraft usernames to Discord nicknames
 */
public class DiscordMinecraftSyncModule extends ListenerAdapter implements CeleryModule {
    
    private final CeleryUtils plugin;
    private JDA jda;
    private boolean enabled = false;
    private final Map<Long, String> minecraftUserMap = new HashMap<>();
    
    public DiscordMinecraftSyncModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public String getName() {
        return "Discord-Minecraft Sync";
    }
    
    @Override
    public boolean initialize() {
        try {
            String token = plugin.getConfig().getString("modules.discord-sync.bot-token");
            if (token == null || token.isEmpty()) {
                plugin.getLogger().warning("Discord bot token not configured!");
                return false;
            }
            
            long guildId = plugin.getConfig().getLong("modules.discord-sync.guild-id");
            if (guildId == 0) {
                plugin.getLogger().warning("Discord guild ID not configured!");
                return false;
            }
            
            // Build JDA instance
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .addEventListeners(this)
                    .build();
            
            jda.awaitReady();
            enabled = true;
            plugin.getLogger().info("Connected to Discord successfully!");
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Discord sync module", e);
            return false;
        }
    }
    
    @Override
    public void disable() {
        if (jda != null) {
            jda.shutdown();
            enabled = false;
        }
    }
    
    @Override
    public boolean isEnabled() {
        return enabled && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }
    
    @Override
    public void onReady(@NotNull ReadyEvent event) {
        plugin.getLogger().info("Discord bot is ready!");
    }
    
    /**
     * Syncs a player's nickname to Discord
     * @param player The player to sync
     */
    public void syncPlayerNickname(Player player) {
        if (!isEnabled()) {
            return;
        }
        
        try {
            long guildId = plugin.getConfig().getLong("modules.discord-sync.guild-id");
            Guild guild = jda.getGuildById(guildId);
            
            if (guild == null) {
                plugin.getLogger().warning("Guild not found: " + guildId);
                return;
            }
            
            // Get Discord ID from player data (stored in minecraftUserMap or config)
            Long discordId = getDiscordIdForPlayer(player.getName());
            if (discordId == null) {
                plugin.getLogger().warning("No Discord ID found for player: " + player.getName());
                return;
            }
            
            Member member = guild.retrieveMemberById(discordId).complete();
            if (member != null && !member.getEffectiveName().equals(player.getName())) {
                member.modifyNickname(player.getName()).queue();
                plugin.getLogger().info("Synced " + player.getName() + " to Discord nickname");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to sync player nickname: " + player.getName(), e);
        }
    }
    
    /**
     * Maps a Discord user ID to a Minecraft username
     */
    public void mapDiscordUser(long discordId, String minecraftUsername) {
        minecraftUserMap.put(discordId, minecraftUsername);
    }
    
    /**
     * Gets the Discord ID for a Minecraft player
     */
    private Long getDiscordIdForPlayer(String playerName) {
        for (Map.Entry<Long, String> entry : minecraftUserMap.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(playerName)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * Gets the JDA instance
     */
    public JDA getJDA() {
        return jda;
    }
}
