package xyz.qincai.celeryutils.modules;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;
import xyz.qincai.celeryutils.command.CommandRegistrar;
import xyz.qincai.celeryutils.command.PvPCommand;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

import xyz.qincai.celeryutils.api.ItemSerialization;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;

public class PvPModule implements CeleryModule, Listener {

    private final CeleryUtils plugin;
    private FileConfiguration config;
    private File configFile;
    
    private File loadoutsFile;
    private FileConfiguration loadoutsConfig;
    
    // Store original inventories for active PvP players
    private final Map<UUID, ItemStack[]> originalInventories = new HashMap<>();
    private final Map<UUID, ItemStack[]> originalArmor = new HashMap<>();
    private final Map<UUID, ItemStack> originalOffhand = new HashMap<>();
    
    private final Set<UUID> activePvpPlayers = new HashSet<>();
    private final Map<UUID, Long> lastCombatTime = new HashMap<>();

    private final Map<UUID, List<ItemStack>> dbLoadouts = new ConcurrentHashMap<>();

    private CommandRegistrar registrar;
    private NamespacedKey pvpItemKey;

    public PvPModule(CeleryUtils plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "pvp-module";
    }

    @Override
    public boolean initialize() {
        this.configFile = new File(plugin.getDataFolder(), "modules/pvp-module/config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("modules/pvp-module/config.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(configFile);

        this.loadoutsFile = new File(plugin.getDataFolder(), "modules/pvp-module/loadouts.yml");
        if (!loadoutsFile.exists()) {
            try {
                loadoutsFile.getParentFile().mkdirs();
                loadoutsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create pvp loadouts file!", e);
                return false;
            }
        }
        this.loadoutsConfig = YamlConfiguration.loadConfiguration(loadoutsFile);

        plugin.getDatabaseManager().executeUpdate("CREATE TABLE IF NOT EXISTS pvp_loadouts (minecraft_uuid VARCHAR(36) PRIMARY KEY, loadout TEXT)");
        loadFromDatabase();

        if (loadoutsFile.exists() && loadoutsConfig.contains("loadouts")) {
            plugin.getLogger().info("Migrating PvP loadouts from YAML to database...");
            ConfigurationSection sec = loadoutsConfig.getConfigurationSection("loadouts");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        List<ItemStack> items = (List<ItemStack>) sec.getList(key);
                        if (items != null) {
                            dbLoadouts.put(uuid, items);
                            saveToDatabase(uuid, items);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to migrate loadout for " + key);
                    }
                }
            }
            loadoutsFile.renameTo(new File(plugin.getDataFolder(), "modules/pvp-module/loadouts.yml.old"));
        }

        this.pvpItemKey = new NamespacedKey(plugin, "pvp_item");

        this.registrar = new CommandRegistrar(plugin);
        registrar.register("pvp", "Toggle PvP loadout or manage gear", new PvPCommand(this, config), true);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        return true;
    }

    @Override
    public void disable() {
        if (registrar != null) registrar.unregisterAll();

        for (UUID uuid : new ArrayList<>(activePvpPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                untogglePvP(player);
            }
        }
        saveLoadouts();
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("modules.pvp-module.enabled", false);
    }

    private void saveToDatabase(UUID uuid, List<ItemStack> loadout) {
        if (loadout == null) {
            plugin.getDatabaseManager().executeUpdate("DELETE FROM pvp_loadouts WHERE minecraft_uuid=?", uuid.toString());
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String encoded = ItemSerialization.toBase64(loadout.toArray(new ItemStack[0]));
                if (plugin.getDatabaseManager().getType() == xyz.qincai.celeryutils.database.DatabaseType.SQLITE) {
                    plugin.getDatabaseManager().executeUpdate("INSERT OR REPLACE INTO pvp_loadouts (minecraft_uuid, loadout) VALUES (?, ?)", uuid.toString(), encoded);
                } else {
                    plugin.getDatabaseManager().executeUpdate("INSERT INTO pvp_loadouts (minecraft_uuid, loadout) VALUES (?, ?) ON DUPLICATE KEY UPDATE loadout=?", uuid.toString(), encoded, encoded);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save pvp loadout to db", e);
            }
        });
    }

    private void loadFromDatabase() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT minecraft_uuid, loadout FROM pvp_loadouts")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("minecraft_uuid"));
                ItemStack[] items = ItemSerialization.fromBase64(rs.getString("loadout"));
                dbLoadouts.put(uuid, Arrays.asList(items));
            }
            plugin.getLogger().info("Loaded " + dbLoadouts.size() + " PvP loadouts from database.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load PvP loadouts from database", e);
        }
    }

    public void saveLoadouts() {
        try {
            if (loadoutsFile.exists()) {
                loadoutsConfig.save(loadoutsFile);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save pvp loadouts!", e);
        }
    }

    public boolean isActivePvpPlayer(UUID uuid) {
        return activePvpPlayers.contains(uuid);
    }

    public void openGearGUI(Player player) {
        int guiSize = config.getInt("options.gui-size", 54);
        if (guiSize < 9 || guiSize > 54 || guiSize % 9 != 0) {
            plugin.getLogger().warning("Invalid gui-size: " + guiSize + " for PvP Loadout. Must be a multiple of 9 and between 9 and 54. Defaulting to 54.");
            guiSize = 54;
        }
        String title = ChatColor.translateAlternateColorCodes('&', config.getString("options.gui-title", "&c&lPvP Loadout"));
        
        Inventory gui = Bukkit.createInventory(player, guiSize, title);
        
        // Load saved items
        List<ItemStack> list = dbLoadouts.get(player.getUniqueId());
        if (list != null) {
            for (int i = 0; i < list.size() && i < guiSize; i++) {
                Object obj = list.get(i);
                if (obj instanceof ItemStack item) {
                    gui.setItem(i, item);
                }
            }
        }
        
        player.openInventory(gui);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.gui-opened")));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = ChatColor.translateAlternateColorCodes('&', config.getString("options.gui-title", "&c&lPvP Loadout"));
        if (!event.getView().getTitle().equals(title)) return;
        
        Player player = (Player) event.getPlayer();
        Inventory gui = event.getInventory();
        
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : gui.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                items.add(new ItemStack(Material.AIR));
            } else {
                items.add(item);
            }
        }
        
        dbLoadouts.put(player.getUniqueId(), items);
        saveToDatabase(player.getUniqueId(), items);
    }

    public void togglePvP(Player player) {
        UUID uuid = player.getUniqueId();
        
        if (lastCombatTime.containsKey(uuid) && System.currentTimeMillis() - lastCombatTime.get(uuid) < 15000) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.cannot-toggle-in-combat", "&cYou cannot toggle PvP mode while in combat!")));
            return;
        }
        
        // Save original inventory
        originalInventories.put(uuid, player.getInventory().getContents().clone());
        originalArmor.put(uuid, player.getInventory().getArmorContents().clone());
        ItemStack offhand = player.getInventory().getItemInOffHand();
        originalOffhand.put(uuid, offhand != null ? offhand.clone() : null);
        
        player.getInventory().clear();
        
        // Give loadout
        List<ItemStack> list = dbLoadouts.get(uuid);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                Object obj = list.get(i);
                if (obj instanceof ItemStack) {
                    ItemStack item = ((ItemStack) obj).clone();
                    
                    // Tag as PvP item
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(pvpItemKey, PersistentDataType.INTEGER, i);
                        item.setItemMeta(meta);
                    }
                    
                    // Try to equip armor
                    String type = item.getType().name();
                    if (type.endsWith("_HELMET") && player.getInventory().getHelmet() == null) {
                        player.getInventory().setHelmet(item);
                    } else if (type.endsWith("_CHESTPLATE") && player.getInventory().getChestplate() == null) {
                        player.getInventory().setChestplate(item);
                    } else if (type.endsWith("_LEGGINGS") && player.getInventory().getLeggings() == null) {
                        player.getInventory().setLeggings(item);
                    } else if (type.endsWith("_BOOTS") && player.getInventory().getBoots() == null) {
                        player.getInventory().setBoots(item);
                    } else if (type.equals("SHIELD") && player.getInventory().getItemInOffHand().getType() == Material.AIR) {
                        player.getInventory().setItemInOffHand(item);
                    } else {
                        player.getInventory().addItem(item);
                    }
                }
            }
        }
        
        activePvpPlayers.add(uuid);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.toggled-on")));
    }

    public void untogglePvP(Player player) {
        UUID uuid = player.getUniqueId();
        
        List<ItemStack> pickUps = new ArrayList<>();
        List<ItemStack> allItems = collectPlayerItems(player);

        List<ItemStack> rawList = dbLoadouts.get(player.getUniqueId());
        if (rawList != null) {
            ItemStack[] updatedLoadout = new ItemStack[rawList.size()];
            for (ItemStack item : allItems) {
                if (item.getType() == Material.AIR) continue;
                if (isPvPItem(item)) {
                    Integer index = item.getItemMeta().getPersistentDataContainer().get(pvpItemKey, PersistentDataType.INTEGER);
                    if (index != null && index >= 0 && index < updatedLoadout.length) {
                        if (updatedLoadout[index] == null) {
                            updatedLoadout[index] = item.clone();
                        } else {
                            int newAmount = updatedLoadout[index].getAmount() + item.getAmount();
                            updatedLoadout[index].setAmount(Math.min(newAmount, updatedLoadout[index].getMaxStackSize()));
                        }
                    }
                } else {
                    pickUps.add(item.clone());
                }
            }
            dbLoadouts.put(player.getUniqueId(), Arrays.asList(updatedLoadout));
            saveToDatabase(player.getUniqueId(), dbLoadouts.get(player.getUniqueId()));
            saveLoadouts();
        } else {
            for (ItemStack item : allItems) {
                if (item.getType() != Material.AIR && !isPvPItem(item)) {
                    pickUps.add(item.clone());
                }
            }
        }

        player.getInventory().clear();
        
        // Restore original inventory (non-pvp items they had before)
        if (originalInventories.containsKey(uuid)) {
            player.getInventory().setContents(originalInventories.get(uuid));
            originalInventories.remove(uuid);
        }
        if (originalArmor.containsKey(uuid)) {
            player.getInventory().setArmorContents(originalArmor.get(uuid));
            originalArmor.remove(uuid);
        }
        if (originalOffhand.containsKey(uuid)) {
            player.getInventory().setItemInOffHand(originalOffhand.get(uuid));
            originalOffhand.remove(uuid);
        }
        
        // Give back items they picked up while in PvP mode
        for (ItemStack item : pickUps) {
            HashMap<Integer, ItemStack> excess = player.getInventory().addItem(item);
            for (ItemStack drop : excess.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
        
        activePvpPlayers.remove(uuid);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.toggled-off")));
    }

    private List<ItemStack> collectPlayerItems(Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) if (item != null) items.add(item);
        for (ItemStack item : player.getInventory().getArmorContents()) if (item != null) items.add(item);
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null) items.add(offhand);
        return items;
    }

    private boolean isPvPItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(pvpItemKey, PersistentDataType.INTEGER);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack currentItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();
        
        boolean currentIsPvP = isPvPItem(currentItem);
        boolean cursorIsPvP = isPvPItem(cursorItem);
        
        if (currentIsPvP || cursorIsPvP) {
            if (event.getClickedInventory() != null && event.getClickedInventory().getType() != org.bukkit.event.inventory.InventoryType.PLAYER && event.getClickedInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                event.setCancelled(true);
                return;
            }
            if (event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
                return;
            }
        }
        
        if (event.getAction() == org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP || event.getAction() == org.bukkit.event.inventory.InventoryAction.HOTBAR_MOVE_AND_READD) {
            int hotbarButton = event.getHotbarButton();
            if (hotbarButton >= 0) {
                ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(hotbarButton);
                if (isPvPItem(hotbarItem)) {
                    if (event.getClickedInventory() != null && event.getClickedInventory().getType() != org.bukkit.event.inventory.InventoryType.PLAYER && event.getClickedInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        ItemStack cursorItem = event.getOldCursor();
        if (isPvPItem(cursorItem)) {
            if (event.getInventory() != null && event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.PLAYER && event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player && event.getDamager() instanceof Player) {
            lastCombatTime.put(event.getEntity().getUniqueId(), System.currentTimeMillis());
            lastCombatTime.put(event.getDamager().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (activePvpPlayers.contains(event.getPlayer().getUniqueId())) {
            untogglePvP(event.getPlayer());
        }
        lastCombatTime.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (isPvPItem(item)) {
            // Can't drop PvP tracked items or they might get exploited
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot drop PvP loadout items!");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (activePvpPlayers.contains(player.getUniqueId())) {
            String mechanic = config.getString("options.death-mechanic", "KEEP_EVERYTHING");
            
            if (mechanic.equalsIgnoreCase("KEEP_EVERYTHING")) {
                event.setKeepInventory(true);
                event.getDrops().clear();
            } else if (mechanic.equalsIgnoreCase("DROP_DUPLICATE")) {
                // Killer loots the damaged duplicates. Strip the PvP tag so they can be picked up.
                for (ItemStack drop : event.getDrops()) {
                    if (isPvPItem(drop)) {
                        ItemMeta meta = drop.getItemMeta();
                        meta.getPersistentDataContainer().remove(pvpItemKey);
                        drop.setItemMeta(meta);
                    }
                }
                
                // Erase their pristine kit from Vault to prevent farming
                dbLoadouts.remove(player.getUniqueId());
                saveToDatabase(player.getUniqueId(), null);
                saveLoadouts();
                
            } else if (mechanic.equalsIgnoreCase("DROP_ORIGINAL")) {
                // Drop the pristine kit from their Vault directly
                List<ItemStack> list = dbLoadouts.get(player.getUniqueId());
                if (list != null) {
                    for (Object obj : list) {
                        if (obj instanceof ItemStack pristine && pristine.getType() != Material.AIR) {
                            player.getWorld().dropItemNaturally(player.getLocation(), pristine.clone());
                        }
                    }
                }
                
                // The damaged duplicates they were wearing must vanish, but let them drop non-PvP items they picked up 
                event.getDrops().removeIf(this::isPvPItem);
                
                // Erase their pristine kit from Vault since it was dropped
                dbLoadouts.remove(player.getUniqueId());
                saveToDatabase(player.getUniqueId(), null);
                saveLoadouts();
            } else {
                // Failsafe: strip tags from drops so we don't leak tagged items
                for (ItemStack drop : event.getDrops()) {
                    if (isPvPItem(drop)) {
                        ItemMeta meta = drop.getItemMeta();
                        meta.getPersistentDataContainer().remove(pvpItemKey);
                        drop.setItemMeta(meta);
                    }
                }
            }
            
            // Cleanly restore their state immediately (so they have original items when respawning) 
            untogglePvP(player);
        }
    }
}
