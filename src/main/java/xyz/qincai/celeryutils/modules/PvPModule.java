package xyz.qincai.celeryutils.modules;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
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
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import xyz.qincai.celeryutils.CeleryUtils;
import xyz.qincai.celeryutils.api.CeleryModule;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class PvPModule implements CeleryModule, Listener, CommandExecutor {

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

        this.pvpItemKey = new NamespacedKey(plugin, "pvp_item");

        PluginCommand pvpCommand = plugin.getCommand("pvp");
        if (pvpCommand != null) {
            pvpCommand.setExecutor(this);
        } else {
            plugin.getLogger().warning("PvP command not found in plugin.yml!");
            return false;
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        return true;
    }

    @Override
    public void disable() {
        // Restore all players before disabling to prevent item loss
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

    public void saveLoadouts() {
        try {
            loadoutsConfig.save(loadoutsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save pvp loadouts!", e);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "Usage: /pvp <gear|toggle>");
            return true;
        }

        if (args[0].equalsIgnoreCase("gear")) {
            if (activePvpPlayers.contains(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "You cannot edit your gear while PvP is active!");
                return true;
            }
            openGearGUI(player);
            return true;
        } else if (args[0].equalsIgnoreCase("toggle")) {
            if (activePvpPlayers.contains(player.getUniqueId())) {
                untogglePvP(player);
            } else {
                togglePvP(player);
            }
            return true;
        }

        player.sendMessage(ChatColor.RED + "Usage: /pvp <gear|toggle>");
        return true;
    }

    private void openGearGUI(Player player) {
        int guiSize = config.getInt("options.gui-size", 54);
        if (guiSize < 9 || guiSize > 54 || guiSize % 9 != 0) {
            plugin.getLogger().warning("Invalid gui-size: " + guiSize + " for PvP Loadout. Must be a multiple of 9 and between 9 and 54. Defaulting to 54.");
            guiSize = 54;
        }
        String title = ChatColor.translateAlternateColorCodes('&', config.getString("options.gui-title", "&c&lPvP Loadout"));
        
        Inventory gui = Bukkit.createInventory(player, guiSize, title);
        
        // Load saved items
        String path = "loadouts." + player.getUniqueId();
        if (loadoutsConfig.contains(path)) {
            List<?> list = loadoutsConfig.getList(path);
            if (list != null) {
                for (int i = 0; i < list.size() && i < guiSize; i++) {
                    Object obj = list.get(i);
                    if (obj instanceof ItemStack) {
                        gui.setItem(i, (ItemStack) obj);
                    }
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
            items.add(item);
        }
        
        loadoutsConfig.set("loadouts." + player.getUniqueId(), items);
        saveLoadouts();
    }

    private void togglePvP(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Save original inventory
        originalInventories.put(uuid, player.getInventory().getContents().clone());
        originalArmor.put(uuid, player.getInventory().getArmorContents().clone());
        originalOffhand.put(uuid, player.getInventory().getItemInOffHand().clone());
        
        player.getInventory().clear();
        
        // Give loadout
        String path = "loadouts." + uuid;
        if (loadoutsConfig.contains(path)) {
            List<?> list = loadoutsConfig.getList(path);
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
        }
        
        activePvpPlayers.add(uuid);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.toggled-on")));
    }

    private void untogglePvP(Player player) {
        UUID uuid = player.getUniqueId();
        
        List<ItemStack> pickUps = new ArrayList<>();
        
        String path = "loadouts." + uuid;
        if (loadoutsConfig.contains(path)) {
            List<?> rawList = loadoutsConfig.getList(path);
            if (rawList != null) {
                ItemStack[] updatedLoadout = new ItemStack[rawList.size()];
                
                List<ItemStack> allItems = new ArrayList<>();
                for (ItemStack item : player.getInventory().getContents()) if (item != null) allItems.add(item);
                for (ItemStack item : player.getInventory().getArmorContents()) if (item != null) allItems.add(item);
                if (player.getInventory().getItemInOffHand() != null) allItems.add(player.getInventory().getItemInOffHand());
                
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
                
                loadoutsConfig.set(path, Arrays.asList(updatedLoadout));
                saveLoadouts();
            }
        } else {
            List<ItemStack> allItems = new ArrayList<>();
            for (ItemStack item : player.getInventory().getContents()) if (item != null) allItems.add(item);
            for (ItemStack item : player.getInventory().getArmorContents()) if (item != null) allItems.add(item);
            if (player.getInventory().getItemInOffHand() != null) allItems.add(player.getInventory().getItemInOffHand());
            
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

    private boolean isPvPItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(pvpItemKey, PersistentDataType.BYTE) || 
               item.getItemMeta().getPersistentDataContainer().has(pvpItemKey, PersistentDataType.INTEGER);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Prevent moving PvP items out of player inventory
        if (event.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.PLAYER 
                && event.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            
            ItemStack currentItem = event.getCurrentItem();
            ItemStack cursorItem = event.getCursor();
            
            boolean currentIsPvP = isPvPItem(currentItem);
            boolean cursorIsPvP = isPvPItem(cursorItem);
            
            // If they interact with top inventory using a PvP item (or hotbar switch)
            if (currentIsPvP || cursorIsPvP) {
                event.setCancelled(true);
                return;
            }
            
            // Check for hotbar swaps
            if (event.getAction() == org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP || event.getAction() == org.bukkit.event.inventory.InventoryAction.HOTBAR_MOVE_AND_READD) {
                int hotbarButton = event.getHotbarButton();
                if (hotbarButton >= 0) {
                    ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(hotbarButton);
                    if (isPvPItem(hotbarItem)) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.PLAYER 
                && event.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            
            ItemStack cursorItem = event.getOldCursor();
            if (isPvPItem(cursorItem)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (activePvpPlayers.contains(event.getPlayer().getUniqueId())) {
            untogglePvP(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Automatically exit pvp mode on quit to ensure items are saved properly
        if (activePvpPlayers.contains(event.getPlayer().getUniqueId())) {
            untogglePvP(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(pvpItemKey, PersistentDataType.BYTE)) {
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
             sPvPItem(item
                        meta.getPersistentDataContainer().remove(pvpItemKey);
                        drop.setItemMeta(meta);
                    }
                }
                
                // Erase their pristine kit from Vault to prevent farming
                loadoutsConfig.set("loadouts." + player.getUniqueId(), null);
                saveLoadouts();
                
            } else if (mechanic.equalsIgnoreCase("DROP_ORIGINAL")) {
                // Drop the pristine kit from their Vault directly
                String path = "loadouts." + player.getUniqueId();
                if (loadoutsConfig.contains(path)) {
                    List<?> list = loadoutsConfig.getList(path);
                    if (list != null) {
                        for (Object obj : list) {
                            if (obj instanceof ItemStack && ((ItemStack) obj).getType() != Material.AIR) {
                                ItemStack pristineItem = ((ItemStack) obj).clone();
                                // Drop pristine item
                                player.getWorld().dropItemNaturally(player.getLocation(), pristineItem);
                            }
                        }
                    }
                }
                
                // The damaged duplicates they were wearing must vanish, but let them drop non-PvP items they picked up 
                event.getDrops().removeIf(this::isPvPItem);
                
                // Erase their pristine kit from Vault since it was dropped
                loadoutsConfig.set(path, null);
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
        }
    }
}
