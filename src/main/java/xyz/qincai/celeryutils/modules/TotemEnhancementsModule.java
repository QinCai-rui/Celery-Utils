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

public class TotemEnhancementsModule implements CeleryModule, Listener {

    private final CeleryUtils plugin;
    private FileConfiguration config;
    private File configFile;

    public TotemEnhancementsModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "totemenhancements";
    }

    @Override
    public boolean initialize() {
        this.configFile = new File(plugin.getDataFolder(), "modules/totemenhancements/config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("modules/totemenhancements/config.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(configFile);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        return true;
    }

    @Override
    public void disable() {
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("modules.totemenhancements.enabled", false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!event.isCancelled()) {
            if (config.getBoolean("hand-totem.broadcast", false)) {
                tryBroadcast(player);
            }
            return;
        }

        String reqPermission = config.getString("permission-node", "celeryutils.totem");
        if (reqPermission != null && !reqPermission.isEmpty() && !player.hasPermission(reqPermission)) {
            return;
        }

        if (!config.getBoolean("inventory-totem.enabled", true)) {
            return;
        }

        PlayerInventory inv = player.getInventory();

        ItemStack mainHand = inv.getItemInMainHand();
        ItemStack offHand = inv.getItemInOffHand();
        if ((mainHand != null && mainHand.getType() == Material.TOTEM_OF_UNDYING)
                || (offHand != null && offHand.getType() == Material.TOTEM_OF_UNDYING)) {
            return;
        }

        boolean foundTotem = false;
        int totemSlot = -1;

        for (int i = 0; i <= 35; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) {
                foundTotem = true;
                totemSlot = i;
                break;
            }
        }

        if (foundTotem) {
            ItemStack totem = inv.getItem(totemSlot);
            if (totem.getAmount() > 1) {
                totem.setAmount(totem.getAmount() - 1);
            } else {
                inv.setItem(totemSlot, null);
            }

            event.setCancelled(false);

            if (config.getBoolean("inventory-totem.activation-message.enabled", true)) {
                String message = config.getString("inventory-totem.activation-message.text",
                        "&aA Totem of Undying in your inventory saved your life!");
                if (message != null && !message.isEmpty()) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
            }

            if (config.getBoolean("inventory-totem.play-effect", true)) {
                player.playEffect(org.bukkit.EntityEffect.TOTEM_RESURRECT);
            }

            if (config.getBoolean("inventory-totem.broadcast", true)) {
                tryBroadcast(player);
            }
        }
    }

    private void tryBroadcast(Player player) {
        String reqPermission = config.getString("permission-node", "celeryutils.totem");
        if (reqPermission != null && !reqPermission.isEmpty() && !player.hasPermission(reqPermission)) {
            return;
        }

        broadcastDeathMessage(player);
    }

    private void broadcastDeathMessage(Player player) {
        String causeName = resolveDeathCause(player);

        String broadcastMsg = config.getString("broadcast-messages.causes." + causeName);
        if (broadcastMsg == null || broadcastMsg.isEmpty()) {
            broadcastMsg = config.getString("broadcast-messages.default",
                    "%player% died but came back to life thanks to &e[Totem Of Undying]&r");
        }

        if (broadcastMsg != null && !broadcastMsg.isEmpty()) {
            broadcastMsg = broadcastMsg.replace("%player%", player.getName());
            plugin.getServer().broadcastMessage(ChatColor.translateAlternateColorCodes('&', broadcastMsg));
        }
    }

    private String resolveDeathCause(Player player) {
        org.bukkit.event.entity.EntityDamageEvent event = player.getLastDamageCause();
        if (event == null) return "DEFAULT";

        org.bukkit.event.entity.EntityDamageEvent.DamageCause cause = event.getCause();

        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) {
            org.bukkit.event.entity.EntityDamageByEntityEvent edbe = (org.bukkit.event.entity.EntityDamageByEntityEvent) event;
            org.bukkit.entity.Entity damager = edbe.getDamager();

            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.PROJECTILE) {
                if (damager instanceof org.bukkit.entity.Trident) {
                    return "TRIDENT_SPEAR";
                }
                if (damager instanceof org.bukkit.entity.EnderPearl) {
                    return "ENDER_PEARL";
                }
            }

            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
                if (damager instanceof org.bukkit.entity.EnderPearl) {
                    return "ENDER_PEARL";
                }
            }

            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                if (damager instanceof org.bukkit.entity.Firework) {
                    return "FIREWORK_EXPLOSION";
                }
            }

            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                if (damager instanceof org.bukkit.entity.Bee) {
                    return "BEE_STING";
                }
                if (damager instanceof Player) {
                    ItemStack weapon = ((Player) damager).getInventory().getItemInMainHand();
                    if (weapon != null && weapon.getType().name().equals("MACE")) {
                        return "MACE_SMASH";
                    }
                }
            }
        }

        if (event instanceof org.bukkit.event.entity.EntityDamageByBlockEvent) {
            org.bukkit.event.entity.EntityDamageByBlockEvent edbb = (org.bukkit.event.entity.EntityDamageByBlockEvent) event;
            org.bukkit.block.Block block = edbb.getDamager();

            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.CONTACT) {
                if (block != null && block.getType().name().contains("BERRY_BUSH")) {
                    return "SWEET_BERRY_BUSH";
                }
            }

            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
                if (block != null && (block.getType().name().contains("BED") || block.getType().name().contains("RESPAWN_ANCHOR"))) {
                    return "INTENTIONAL_GAME_DESIGN";
                }
            }

            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALLING_BLOCK) {
                if (block != null && block.getType().name().contains("ANVIL")) {
                    return "FALLING_ANVIL";
                }
            }
        }

        if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
            org.bukkit.block.Block currentBlock = player.getLocation().getBlock();
            if (currentBlock.getType().name().contains("POINTED_DRIPSTONE")) {
                return "FALLING_ON_STALAGMITE";
            }
        }

        return cause.name();
    }
}
