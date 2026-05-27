package xyz.qincai.celeryutils.modules;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;

public class InventoryTotemModule implements CeleryModule, Listener {

    private final CeleryUtils plugin;
    private FileConfiguration config;
    private File configFile;

    public InventoryTotemModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "inventory-totem";
    }

    @Override
    public boolean initialize() {
        this.configFile = new File(plugin.getDataFolder(), "modules/inventory-totem/config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("modules/inventory-totem/config.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(configFile);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        return true;
    }

    @Override
    public void disable() {
        // Nothing special to disable
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("modules.inventory-totem.enabled", false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();

        // If the event isn't cancelled, it means the player was already holding a totem.
        if (!event.isCancelled()) {
            return;
        }

        String reqPermission = config.getString("permission-node", "celeryutils.totem");
        if (reqPermission != null && !reqPermission.isEmpty() && !player.hasPermission(reqPermission)) {
            return;
        }

        PlayerInventory inv = player.getInventory();

        // If the player already has an active totem in hand, let vanilla handle the process.
        ItemStack mainHand = inv.getItemInMainHand();
        ItemStack offHand = inv.getItemInOffHand();
        if ((mainHand != null && mainHand.getType() == Material.TOTEM_OF_UNDYING)
                || (offHand != null && offHand.getType() == Material.TOTEM_OF_UNDYING)) {
            return;
        }

        boolean foundTotem = false;
        int totemSlot = -1;

        // Search for a totem in the main inventory (slots 0-35)
        for (int i = 0; i <= 35; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) {
                foundTotem = true;
                totemSlot = i;
                break;
            }
        }

        if (foundTotem) {
            // Consume the totem
            ItemStack totem = inv.getItem(totemSlot);
            if (totem.getAmount() > 1) {
                totem.setAmount(totem.getAmount() - 1);
            } else {
                inv.setItem(totemSlot, null);
            }

            // Un-cancel the event to resurrect the player
            event.setCancelled(false);

            // Send custom message if enabled
            if (config.getBoolean("send-activation-message", true)) {
                String message = config.getString("activation-message", "&aA Totem of Undying in your inventory saved your life!");
                if (message != null && !message.isEmpty()) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
            }
            
            // Play effect simply in case vanilla doesn't animate properly when the item isn't in hand
            if (config.getBoolean("play-totem-effect", true)) {
                player.playEffect(org.bukkit.EntityEffect.TOTEM_RESURRECT);
            }
        }
    }
}
