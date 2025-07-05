package me.lampa.customrules;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CustomRules extends JavaPlugin implements Listener, CommandExecutor {

    private final Random random = new Random();
    private final Set<String> activeMaceIds = new HashSet<>();
    private File maceFile;
    private YamlConfiguration maceData;
    private final HashMap<UUID, Long> combatTimestamps = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("clearnetherite").setExecutor(this);
        loadMaceData();
    }

    @Override
    public void onDisable() {
        saveMaceData();
    }

    private void loadMaceData() {
        maceFile = new File(getDataFolder(), "maces.yml");
        File folder = getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            getLogger().warning("Could not create data folder!");
        }
        if (!maceFile.exists()) {
            try {
                maceFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        maceData = YamlConfiguration.loadConfiguration(maceFile);
        activeMaceIds.clear();
        List<String> ids = maceData.getStringList("activeMaces");
        activeMaceIds.addAll(ids);
    }

    private void saveMaceData() {
        maceData.set("activeMaces", new ArrayList<>(activeMaceIds));
        try {
            maceData.save(maceFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onMaceCraft(CraftItemEvent event) {
        if (event.getRecipe().getResult().getType() != Material.MACE) return;
        if (activeMaceIds.size() >= 2) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendMessage("§cMax 2 maces are allowed on this server.");
            }
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() != Material.MACE) return;
        String id = UUID.randomUUID().toString();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "mace_id"), PersistentDataType.STRING, id);
            meta.setDisplayName("§6Server-Mace");
            result.setItemMeta(meta);
        }
        activeMaceIds.add(id);
        saveMaceData();
        getLogger().info("Mace registered on craft: " + id);
    }

    @EventHandler
    public void onAutoCraftAttempt(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null || event.getRecipe().getResult().getType() != Material.MACE) return;
        if (!(event.getView().getPlayer() instanceof Player)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onMacePickup(PlayerPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (item.getType() == Material.MACE) {
            String id = getMaceId(item);
            if (id == null) {
                event.setCancelled(true);
                event.getItem().remove();
                event.getPlayer().sendMessage("§cIllegal mace removed.");
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item != null && item.getType() == Material.MACE && !hasValidMaceId(item)) {
            event.setCancelled(true);
            event.setCurrentItem(null);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendMessage("§cIllegal mace blocked.");
            }
        }
    }

    private boolean hasValidMaceId(ItemStack item) {
        return getMaceId(item) != null;
    }

    private String getMaceId(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(new NamespacedKey(this, "mace_id"), PersistentDataType.STRING);
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        checkIfMaceRemoved(event.getEntity().getItemStack());
    }

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() != Material.MACE) return;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable) {
            int durabilityLeft = item.getType().getMaxDurability() - damageable.getDamage();
            if (durabilityLeft - event.getDamage() <= 0) {
                checkIfMaceRemoved(item);
            }
        }
    }

    @EventHandler
    public void onItemDestroy(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item) {
            EntityDamageEvent.DamageCause cause = event.getCause();
            if (cause == EntityDamageEvent.DamageCause.LAVA || cause == EntityDamageEvent.DamageCause.VOID ||
                    cause == EntityDamageEvent.DamageCause.FIRE || cause == EntityDamageEvent.DamageCause.FIRE_TICK) {
                checkIfMaceRemoved(item.getItemStack());
            }
        }
    }

    private void checkIfMaceRemoved(ItemStack item) {
        String id = getMaceId(item);
        if (id != null && activeMaceIds.contains(id)) {
            activeMaceIds.remove(id);
            saveMaceData();
            getLogger().info("Mace removed: " + id);
            Bukkit.broadcastMessage("§cOne mace have dissapeared. Anyone can craft a new one.");
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntityType() == EntityType.WITHER) {
            if (random.nextDouble() < 0.25) {
                event.getDrops().add(new ItemStack(Material.ANCIENT_DEBRIS));
                Bukkit.broadcastMessage("§6Wither dropped Ancient Debris!");
            }
        }
    }

    @EventHandler
    public void onCrystalPlace(PlayerInteractEvent event) {
        if (event.getItem() != null && event.getItem().getType() == Material.END_CRYSTAL) {
            Material block = event.getClickedBlock() != null ? event.getClickedBlock().getType() : Material.AIR;
            if (block != Material.BEDROCK) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cCrystals can only be placed on bedrock.");
            }
        }
    }

    @EventHandler
    public void onCrystalExplode(EntityExplodeEvent event) {
        if (event.getEntityType() == EntityType.END_CRYSTAL) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.ANCIENT_DEBRIS) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYou can mine Ancient Debris.");
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        int minY = chunk.getWorld().getMinHeight();
        int maxY = chunk.getWorld().getMaxHeight();
        for (int x = 0; x < 16; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.getType() == Material.ANCIENT_DEBRIS || block.getType() == Material.NETHERITE_BLOCK) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onLoot(LootGenerateEvent event) {
        event.getLoot().removeIf(item ->
                item.getType().name().contains("NETHERITE")
        );
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() == Material.NETHERITE_BLOCK) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYou cant place netherite block.");
        }
    }

    @EventHandler
    public void onTotemClick(InventoryClickEvent event) {
        if (event.getCurrentItem() != null &&
                event.getCurrentItem().getType() == Material.TOTEM_OF_UNDYING) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage("§cTotems are banned.");
        }
    }

    @EventHandler
    public void onPickupTotem(PlayerPickupItemEvent event) {
        if (event.getItem().getItemStack().getType() == Material.TOTEM_OF_UNDYING) {
            event.setCancelled(true);
            event.getItem().remove();
            event.getPlayer().sendMessage("§cTotems are banned on this server.");
        }
    }

    @EventHandler
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player attacker) {
            long now = System.currentTimeMillis();
            combatTimestamps.put(victim.getUniqueId(), now);
            combatTimestamps.put(attacker.getUniqueId(), now);
        }
    }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            Long lastCombat = combatTimestamps.get(uuid);
            if (lastCombat != null && (System.currentTimeMillis() - lastCombat) < 10_000) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot use elytra within 10 seconds of PvP.");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("clearnetherite")) {
            if (!(sender instanceof ConsoleCommandSender) && !(sender.hasPermission("customrules.admin"))) {
                sender.sendMessage("§cYou do not have permission to use this command..");
                return true;
            }

            int removed = 0;

            for (Player player : Bukkit.getOnlinePlayers()) {
                removed += removeNetheriteFromInventory(player.getInventory());
                removed += removeNetheriteFromInventory(player.getEnderChest());
            }

            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    removed += removeNetheriteBlocksInChunk(chunk);
                    for (BlockState tile : chunk.getTileEntities()) {
                        if (tile instanceof Container container) {
                            removed += removeNetheriteFromInventory(container.getInventory());
                        }
                    }
                }
            }

            sender.sendMessage("§aTotal netherite items/blocks removed: " + removed);
            Bukkit.broadcastMessage("§c[CustomRules] All netherite in the world has been removed.");
            return true;
        }
        return false;
    }

    private int removeNetheriteFromInventory(Inventory inv) {
        int removed = 0;
        for (ItemStack item : inv.getContents()) {
            if (item == null) continue;
            if (isNetherite(item.getType())) {
                removed += item.getAmount();
                inv.remove(item);
            }
        }
        return removed;
    }

    private int removeNetheriteBlocksInChunk(Chunk chunk) {
        int removed = 0;
        int minY = chunk.getWorld().getMinHeight();
        int maxY = chunk.getWorld().getMaxHeight();
        for (int x = 0; x < 16; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.getType() == Material.NETHERITE_BLOCK) {
                        block.setType(Material.AIR);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private boolean isNetherite(Material mat) {
        return mat.name().contains("NETHERITE");
    }
}
