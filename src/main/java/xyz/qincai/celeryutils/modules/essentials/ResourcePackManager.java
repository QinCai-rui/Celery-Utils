package xyz.qincai.celeryutils.modules.essentials;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import xyz.qincai.celeryutils.CeleryUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public class ResourcePackManager implements Listener {

    private final CeleryUtils plugin;
    private final FileConfiguration moduleConfig;
    private final List<Pack> packs = new ArrayList<>();
    private boolean enabled;
    private int delayTicks;

    public ResourcePackManager(CeleryUtils plugin, FileConfiguration moduleConfig) {
        this.plugin = plugin;
        this.moduleConfig = moduleConfig;
    }

    public void initialize() {
        this.enabled = moduleConfig.getBoolean("resource-packs.enabled", false);
        this.delayTicks = moduleConfig.getInt("resource-packs.delay-ticks", 20);
        if (!enabled) return;
        loadPacks();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        packs.clear();
    }

    public void reload() {
        packs.clear();
        loadPacks();
    }

    private void loadPacks() {
        File packFile = new File(plugin.getDataFolder(), "modules/essentials/packs.yml");
        if (!packFile.exists()) {
            plugin.saveResource("modules/essentials/packs.yml", false);
            packFile = new File(plugin.getDataFolder(), "modules/essentials/packs.yml");
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(packFile);
        ConfigurationSection section = config.getConfigurationSection("packs");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) continue;

            String url = entry.getString("url");
            if (url == null || url.isBlank()) {
                plugin.getLogger().warning("Resource pack '" + key + "' has no URL, skipping.");
                continue;
            }

            String hashStr = entry.getString("hash");
            byte[] hash = null;
            if (hashStr != null && !hashStr.isBlank()) {
                try {
                    hash = HexFormat.of().parseHex(hashStr);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid hash for resource pack '" + key + "': " + hashStr);
                }
            }

            String prompt = entry.getString("prompt");
            boolean required = entry.getBoolean("required", false);
            String permission = entry.getString("permission");
            List<String> worlds = entry.getStringList("worlds");
            if (worlds.isEmpty()) worlds = null;

            packs.add(new Pack(key, url, hash, prompt, required, permission, worlds));
        }

        plugin.getLogger().info("Loaded " + packs.size() + " resource pack(s)");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled || delayTicks < 0) return;
        Player player = event.getPlayer();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Pack pack : packs) {
                if (!pack.appliesTo(player)) continue;
                player.addResourcePack(
                    pack.uuid(),
                    pack.url(),
                    pack.hash(),
                    pack.prompt(),
                    pack.required()
                );
            }
        }, delayTicks);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private record Pack(
        String id,
        String url,
        byte[] hash,
        String prompt,
        boolean required,
        String permission,
        List<String> worlds
    ) {
        private static final UUID NAMESPACE = UUID.fromString("a9e3b8c0-1d4f-4e6b-8c7a-2d5f9e0a1b3c");

        UUID uuid() {
            return UUID.nameUUIDFromBytes((NAMESPACE.toString() + ":" + id).getBytes());
        }

        boolean appliesTo(Player player) {
            if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
                return false;
            }
            if (worlds != null && !worlds.isEmpty() && !worlds.contains(player.getWorld().getName())) {
                return false;
            }
            return true;
        }
    }
}
