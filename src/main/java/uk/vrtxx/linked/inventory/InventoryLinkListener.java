package uk.vrtxx.linked.inventory;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import uk.vrtxx.linked.config.LinkSettings;

/** Synchronizes all inventory-changing actions to the shared inventory. */
public final class InventoryLinkListener implements Listener {

  private final JavaPlugin plugin;
  private final SharedInventory sharedInventory;
  private final LinkSettings linkSettings;

  private final Set<UUID> pendingPlayers = new LinkedHashSet<>();
  private final Set<UUID> miningPlayers = new LinkedHashSet<>();
  private final Set<UUID> deferredSyncPlayers = new LinkedHashSet<>();
  private SharedInventory.Snapshot pendingBaseline;
  private boolean syncScheduled;

  public InventoryLinkListener(
      JavaPlugin plugin, SharedInventory sharedInventory, LinkSettings linkSettings) {
    this.plugin = plugin;
    this.sharedInventory = sharedInventory;
    this.linkSettings = linkSettings;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!linkSettings.isInventoryLinkEnabled()) {
                return;
              }

              if (!sharedInventory.isInitialized()) {
                sharedInventory.capture(event.getPlayer());
              }

              syncAllPlayersWithoutInterruptingMining();
            });
  }

  @EventHandler
  public void onRespawn(PlayerRespawnEvent event) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (linkSettings.isInventoryLinkEnabled()) {
                syncAllPlayersWithoutInterruptingMining();
              }
            });
  }

  /**
   * A cursor item and crafting matrix do not belong to PlayerInventory. Move them into the shared
   * inventory before Bukkit tears the view down, so a following sync cannot overwrite them.
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onInventoryClose(InventoryCloseEvent event) {
    if (event.getPlayer() instanceof Player player) {
      returnTransientItems(player, event.getInventory(), false);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    miningPlayers.remove(event.getPlayer().getUniqueId());
    deferredSyncPlayers.remove(event.getPlayer().getUniqueId());
    returnTransientItems(
        event.getPlayer(), event.getPlayer().getOpenInventory().getTopInventory(), true);
  }

  /**
   * Do not replace a player's held item while their client is rendering block-breaking progress.
   */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockDamage(BlockDamageEvent event) {
    if (!event.getInstaBreak()) {
      miningPlayers.add(event.getPlayer().getUniqueId());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    UUID uuid = event.getPlayer().getUniqueId();
    miningPlayers.remove(uuid);
    // The just-finished miner is the authoritative source for their tool damage and new drops.
    deferredSyncPlayers.remove(uuid);
    handleMutation(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onBlockDamageAbort(BlockDamageAbortEvent event) {
    Player player = event.getPlayer();
    miningPlayers.remove(player.getUniqueId());
    syncDeferredPlayer(player);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      handleMutation(player);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      handleMutation(player);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player) {
      handleMutation(player);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDrop(PlayerDropItemEvent event) {
    handleMutation(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onConsume(PlayerItemConsumeEvent event) {
    handleMutation(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onItemDamage(PlayerItemDamageEvent event) {
    handleMutation(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onArmorChange(PlayerArmorChangeEvent event) {
    handleMutation(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSwap(PlayerSwapHandItemsEvent event) {
    handleMutation(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent event) {
    handleMutation(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBucketEmpty(PlayerBucketEmptyEvent event) {
    handleMutation(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBucketFill(PlayerBucketFillEvent event) {
    handleMutation(event.getPlayer());
  }

  /** Called when a crossbow has finished loading its projectiles. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCrossbowLoad(EntityLoadCrossbowEvent event) {
    Entity entity = event.getEntity();

    if (entity instanceof Player player) {
      handleMutation(player);
    }
  }

  /** Called when a bow or crossbow shoots a projectile. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBowShoot(EntityShootBowEvent event) {
    Entity shooter = event.getEntity();

    if (shooter instanceof Player player) {
      handleMutation(player);
    }
  }

  /** Captures consumable projectiles such as pearls, throwable potions, and experience bottles. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onProjectileLaunch(ProjectileLaunchEvent event) {
    Projectile projectile = event.getEntity();

    if (projectile.getShooter() instanceof Player player) {
      handleMutation(player);
    }
  }

  /**
   * Queues inventory changes and captures them on the next tick. Events are never cancelled by this
   * listener.
   */
  private void handleMutation(Player player) {
    if (!linkSettings.isInventoryLinkEnabled()) {
      return;
    }

    if (!syncScheduled) {
      pendingBaseline = sharedInventory.snapshot();
    }
    pendingPlayers.add(player.getUniqueId());

    if (syncScheduled) {
      return;
    }

    syncScheduled = true;

    Bukkit.getScheduler().runTask(plugin, this::processPendingMutations);
  }

  private void processPendingMutations() {
    try {
      if (!linkSettings.isInventoryLinkEnabled()) {
        pendingPlayers.clear();
        return;
      }

      // Merge each player's slot changes against the same pre-batch snapshot. Capturing each
      // complete inventory here would let the last pending player overwrite unrelated changes.
      for (UUID uuid : pendingPlayers) {
        Player player = Bukkit.getPlayer(uuid);

        if (player != null && player.isOnline()) {
          sharedInventory.mergeChangesFrom(player, pendingBaseline);
        }
      }

      pendingPlayers.clear();
      syncAllPlayersWithoutInterruptingMining();
    } finally {
      syncScheduled = false;
      pendingBaseline = null;

      // Schedule another pass if more changes arrived.
      if (!pendingPlayers.isEmpty()) {
        Player player = Bukkit.getPlayer(pendingPlayers.iterator().next());

        if (player != null) {
          handleMutation(player);
        }
      }
    }
  }

  private void returnTransientItems(Player player, Inventory inventory, boolean playerIsQuitting) {
    if (!linkSettings.isInventoryLinkEnabled()) {
      return;
    }

    List<ItemStack> items = new ArrayList<>();
    ItemStack cursor = player.getItemOnCursor();
    if (hasItem(cursor)) {
      items.add(cursor.clone());
      player.setItemOnCursor(null);
    }

    if (inventory instanceof CraftingInventory craftingInventory) {
      for (ItemStack item : craftingInventory.getMatrix()) {
        if (hasItem(item)) {
          items.add(item.clone());
        }
      }
      craftingInventory.setMatrix(new ItemStack[craftingInventory.getMatrix().length]);
    }

    if (items.isEmpty()) {
      return;
    }

    // Return items to the source inventory first. The resulting changes are then merged just like
    // any other mutation, rather than making this one player's complete inventory authoritative.
    // A full shared inventory drops only the genuine overflow once, rather than voiding it.
    for (ItemStack item : player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()) {
      player.getWorld().dropItemNaturally(player.getLocation(), item);
    }

    if (!playerIsQuitting) {
      handleMutation(player);
      return;
    }

    // A quit has no next tick on which to process this player. Use an in-flight batch's baseline
    // when present so this final change cannot replace slots changed by other players that tick.
    SharedInventory.Snapshot baseline =
        pendingBaseline == null ? sharedInventory.snapshot() : pendingBaseline;
    sharedInventory.mergeChangesFrom(player, baseline);
    pendingPlayers.remove(player.getUniqueId());
    syncAllPlayersWithoutInterruptingMining();
  }

  private static boolean hasItem(ItemStack item) {
    return item != null && !item.getType().isAir() && item.getAmount() > 0;
  }

  private void syncAllPlayersWithoutInterruptingMining() {
    deferredSyncPlayers.addAll(miningPlayers);
    sharedInventory.syncAllPlayersExcept(miningPlayers);
  }

  private void syncDeferredPlayer(Player player) {
    if (!deferredSyncPlayers.contains(player.getUniqueId())) {
      return;
    }
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              UUID uuid = player.getUniqueId();
              if (!player.isOnline() || miningPlayers.contains(uuid)) {
                return;
              }
              if (deferredSyncPlayers.remove(uuid)) {
                sharedInventory.apply(player);
              }
            });
  }
}
