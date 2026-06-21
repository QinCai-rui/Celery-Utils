package xyz.qincai.celeryutils.modules;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TotemEnhancementsModule implements CeleryModule, Listener {

    private final CeleryUtils plugin;
    private FileConfiguration config;
    private File configFile;

    private final Set<UUID> voidVictims = new HashSet<>();
    private final Set<UUID> voidEscapeActive = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> resurrectTick = new ConcurrentHashMap<>();
    private final Set<UUID> voidTotemDeaths = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> levitationTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fastLevitationTicks = new ConcurrentHashMap<>();

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

        HandlerList.unregisterAll(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        return true;
    }

    @Override
    public void disable() {
        for (BukkitTask task : levitationTasks.values()) {
            task.cancel();
        }
        levitationTasks.clear();
        voidEscapeActive.clear();
        voidVictims.clear();
        resurrectTick.clear();
        voidTotemDeaths.clear();
        fastLevitationTicks.clear();

        HandlerList.unregisterAll(this);
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

        UUID uuid = player.getUniqueId();

        // Prevent duplicate processing from multiple firings in the same tick
        int currentTick = Bukkit.getCurrentTick();
        Integer lastTick = resurrectTick.get(uuid);
        if (lastTick != null && lastTick == currentTick) return;
        resurrectTick.put(uuid, currentTick);

        boolean wasVoidVictim = voidVictims.remove(uuid);
        if (wasVoidVictim) {
            voidTotemDeaths.add(uuid);
        }

        if (!event.isCancelled()) {
            // If totem already consumed, this is a duplicate event — skip broadcast
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();
            boolean hasTotem = mainHand.getType() == Material.TOTEM_OF_UNDYING
                    || offHand.getType() == Material.TOTEM_OF_UNDYING;

            if (hasTotem) {
                if (wasVoidVictim) {
                    tryBroadcastVoid(player);
                } else if (config.getBoolean("hand-totem.broadcast", false)) {
                    tryBroadcast(player);
                }
            }

            if (wasVoidVictim && config.getBoolean("void-totem.hand", true)) {
                sendVoidTotemMessage(player);
                startVoidEscape(player);
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

            Advancement advancement = Bukkit.getAdvancement(NamespacedKey.minecraft("adventure/totem_of_undying"));
            if (advancement != null) {
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                if (!progress.isDone()) {
                    for (String criteria : progress.getRemainingCriteria()) {
                        progress.awardCriteria(criteria);
                    }
                }
            }

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

            if (wasVoidVictim) {
                tryBroadcastVoid(player);
            } else if (config.getBoolean("inventory-totem.broadcast", true)) {
                tryBroadcast(player);
            }

            if (wasVoidVictim && config.getBoolean("void-totem.inventory", true)) {
                sendVoidTotemMessage(player);
                startVoidEscape(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID) return;
        if (!(event.getEntity() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();

        // During void escape: cancel void damage if ascending above void or normally floating up
        if (voidEscapeActive.contains(uuid)) {
            if (player.getLocation().getY() < config.getInt("void-totem.trigger-y", -64)) {
                if (player.isSneaking()) {
                    // Sneaking below void threshold → intentionally descending → let them die
                } else {
                    // Fell back below threshold — restart fast levitation burst
                    fastLevitationTicks.put(uuid, 20);
                    event.setCancelled(true);
                }
            } else {
                event.setCancelled(true);
            }
            return;
        }

        if (voidVictims.contains(uuid)) {
            event.setCancelled(true);
            return;
        }

        if (!config.getBoolean("void-totem.enabled", true)) return;
        if (player.getLocation().getY() >= config.getInt("void-totem.trigger-y", -64)) return;

        if (hasTotemForVoid(player)) {
            voidVictims.add(uuid);
            // Void damage bypasses totems in Minecraft, so cancel it
            event.setCancelled(true);
            // Apply non-void damage on next tick to trigger the totem
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) {
                        voidVictims.remove(uuid);
                        voidTotemDeaths.remove(uuid);
                        return;
                    }
                    player.damage(1000, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build());
                }
            }.runTask(plugin);
        }
    }

    private boolean hasTotemForVoid(Player player) {
        boolean hand = config.getBoolean("void-totem.hand", true);
        boolean inventory = config.getBoolean("void-totem.inventory", true);

        if (hand) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (mainHand.getType() == Material.TOTEM_OF_UNDYING || offHand.getType() == Material.TOTEM_OF_UNDYING) {
                return true;
            }
        }

        if (inventory) {
            for (int i = 0; i <= 35; i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) {
                    return true;
                }
            }
        }

        return false;
    }

    private void startVoidEscape(Player player) {
        UUID uuid = player.getUniqueId();

        // Cancel any existing escape task before starting a new one
        BukkitTask oldTask = levitationTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();
        voidEscapeActive.add(uuid);
        fastLevitationTicks.put(uuid, 20);

        int duration = config.getInt("void-totem.duration", 60);

        player.sendActionBar(Component.text(ChatColor.translateAlternateColorCodes('&',
                "&a" + duration + "s &7| &eHold sneak to descend")));

        BukkitTask task = new BukkitRunnable() {
            int ticksLeft = duration * 20;
            boolean wasSneaking = false;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cleanupPlayer(player);
                    return;
                }

                if (player.isOnGround()) {
                    cleanupPlayer(player);
                    return;
                }

                boolean sneaking = player.isSneaking();

                if (ticksLeft > 0) {
                    if (sneaking) {
                        player.removePotionEffect(PotionEffectType.LEVITATION);
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 40, 0, true, false, true));
                    } else {
                        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                        Integer fastLeft = fastLevitationTicks.get(uuid);
                        if (fastLeft != null && fastLeft > 0) {
                            player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 40, 63, true, false, true));
                            fastLevitationTicks.put(uuid, fastLeft - 1);
                        } else {
                            player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 40, 4, true, false, true));
                        }
                    }
                    ticksLeft--;

                    if (ticksLeft % 20 == 0 || sneaking != wasSneaking) {
                        if (sneaking) {
                            player.sendActionBar(Component.text(ChatColor.translateAlternateColorCodes('&',
                                    "&a" + (ticksLeft / 20) + "s &7| &aDescending... &7(sneak)")));
                        } else {
                            player.sendActionBar(Component.text(ChatColor.translateAlternateColorCodes('&',
                                    "&a" + (ticksLeft / 20) + "s &7| &eHold sneak to descend")));
                        }
                    }
                    wasSneaking = sneaking;
                } else {
                    cleanupPlayer(player);
                    return;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        levitationTasks.put(uuid, task);
    }

    private void sendVoidTotemMessage(Player player) {
        if (config.getBoolean("void-totem.activation-message.enabled", true)) {
            String message = config.getString("void-totem.activation-message.text",
                    "&cYou fell into the void but &e[Totem Of Undying]&r pulled you back");
            if (message != null && !message.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        }
    }

    private void cleanupPlayer(Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = levitationTasks.remove(uuid);
        if (task != null) task.cancel();

        voidEscapeActive.remove(uuid);
        voidVictims.remove(uuid);
        voidTotemDeaths.remove(uuid);
        fastLevitationTicks.remove(uuid);

        player.removePotionEffect(PotionEffectType.LEVITATION);
        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private void tryBroadcastVoid(Player player) {
        if (!config.getBoolean("void-totem.broadcast", true)) return;

        String reqPermission = config.getString("permission-node", "celeryutils.totem");
        if (reqPermission != null && !reqPermission.isEmpty() && !player.hasPermission(reqPermission)) {
            return;
        }

        String msg = config.getString("void-totem.broadcast-message");
        if (msg != null && !msg.isEmpty()) {
            msg = msg.replace("%player%", player.getName());
            plugin.getServer().broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
        } else {
            broadcastDeathMessage(player);
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
        String causeName;
        if (voidTotemDeaths.remove(player.getUniqueId())) {
            causeName = "VOID";
        } else {
            causeName = resolveDeathCause(player);
        }
        String broadcastMsg = null;

        if (causeName.equals("ENTITY_ATTACK") || causeName.equals("ENTITY_SWEEP_ATTACK")
                || causeName.equals("PROJECTILE") || causeName.equals("ENTITY_EXPLOSION")) {
            String entityType = resolveEntityType(player);
            if (entityType != null) {
                broadcastMsg = config.getString("broadcast-messages.entity-attacks." + entityType);
                if (broadcastMsg == null || broadcastMsg.isEmpty()) {
                    String formattedEntity = formatEntityName(entityType);
                    broadcastMsg = config.getString("broadcast-messages.entity-attacks.default");
                    if (broadcastMsg != null) {
                        broadcastMsg = broadcastMsg.replace("%entity%", formattedEntity);
                    }
                }
            }
        }

        if (broadcastMsg == null || broadcastMsg.isEmpty()) {
            broadcastMsg = config.getString("broadcast-messages.causes." + causeName);
        }
        if (broadcastMsg == null || broadcastMsg.isEmpty()) {
            broadcastMsg = config.getString("broadcast-messages.default",
                    "%player% died but came back to life thanks to &e[Totem Of Undying]&r");
        }

        if (broadcastMsg != null && !broadcastMsg.isEmpty()) {
            broadcastMsg = broadcastMsg.replace("%player%", player.getName());
            plugin.getServer().broadcastMessage(ChatColor.translateAlternateColorCodes('&', broadcastMsg));
        }
    }

    private String resolveEntityType(Player player) {
        org.bukkit.event.entity.EntityDamageEvent event = player.getLastDamageCause();
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent edbe) {
            Entity damager = edbe.getDamager();
            if (damager instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                return shooter.getType().name();
            }
            return damager.getType().name();
        }
        return null;
    }

    private String formatEntityName(String entityTypeName) {
        StringBuilder result = new StringBuilder();
        boolean nextUpperCase = true;
        for (char c : entityTypeName.toCharArray()) {
            if (c == '_') {
                result.append(' ');
                nextUpperCase = true;
            } else if (nextUpperCase) {
                result.append(Character.toUpperCase(c));
                nextUpperCase = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
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
