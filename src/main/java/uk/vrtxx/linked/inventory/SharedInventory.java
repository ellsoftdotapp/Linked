package uk.vrtxx.linked.inventory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/** Holds, applies, and persists the inventory shared by every online player. */
public final class SharedInventory {

  private static final int STORAGE_SIZE = 36;
  private static final int ARMOR_SIZE = 4;
  private static final int ENDER_CHEST_SIZE = 27;
  private static final int FILE_MAGIC_V1 = 0x4C4E4B31; // LNK1
  private static final int FILE_MAGIC = 0x4C4E4B32; // LNK2
  private static final int MAX_SECTION_BYTES = 16 * 1024 * 1024;

  private final JavaPlugin plugin;
  private final File inventoryFile;
  private final File legacyInventoryFile;

  private ItemStack[] storage = new ItemStack[STORAGE_SIZE];
  private ItemStack[] armor = new ItemStack[ARMOR_SIZE];
  private ItemStack[] enderChest = new ItemStack[ENDER_CHEST_SIZE];
  private ItemStack offhand;
  private boolean initialized;
  private Object nativeStorage;
  private UUID nativeStorageOwner;
  private boolean nativeStorageUnavailable;

  public SharedInventory(JavaPlugin plugin) {
    this.plugin = plugin;
    inventoryFile = new File(plugin.getDataFolder(), "shared-inventory.nbt");
    legacyInventoryFile = new File(plugin.getDataFolder(), "shared-inventory.dat");
  }

  public boolean isInitialized() {
    return initialized;
  }

  public void capture(Player player) {
    PlayerInventory inventory = player.getInventory();
    storage = cloneItems(inventory.getStorageContents());
    armor = cloneItems(inventory.getArmorContents());
    offhand = cloneItem(inventory.getItemInOffHand());
    enderChest = cloneItems(player.getEnderChest().getContents());
    initialized = true;
  }

  /**
   * Merges the slots changed by {@code player} since {@code baseline} into the current shared
   * inventory.
   *
   * <p>Several players can mutate the shared inventory in one server tick. Replacing the complete
   * inventory for each of them makes the last player processed overwrite unrelated slot changes
   * from the others. Comparing each player with the common state from before the batch lets those
   * independent slot changes coexist. If two players change the same slot, the later event remains
   * authoritative.
   */
  public void mergeChangesFrom(Player player, Snapshot baseline) {
    PlayerInventory inventory = player.getInventory();
    if (!isStorageShared()) {
      storage = mergeChangedItems(storage, baseline.storage, inventory.getStorageContents());
    }
    armor = mergeChangedItems(armor, baseline.armor, inventory.getArmorContents());
    offhand =
        sameItem(baseline.offhand, inventory.getItemInOffHand())
            ? offhand
            : cloneItem(inventory.getItemInOffHand());
    enderChest =
        mergeChangedItems(enderChest, baseline.enderChest, player.getEnderChest().getContents());
    initialized = true;
  }

  /** Returns an immutable copy of the state used to detect changes in a sync batch. */
  public Snapshot snapshot() {
    return new Snapshot(storage, armor, offhand, enderChest);
  }

  public void apply(Player player) {
    PlayerInventory inventory = player.getInventory();
    boolean storageIsShared = shareNativeStorage(player);
    if (!storageIsShared) {
      inventory.setStorageContents(cloneItems(storage));
    }
    inventory.setArmorContents(cloneItems(armor));
    inventory.setItemInOffHand(cloneItem(offhand));
    player.getEnderChest().setContents(cloneItems(enderChest));
    if (storageIsShared) {
      // A native list mutation is observed by the server immediately, but other players' clients
      // still need an inventory packet because their own Inventory#setChanged was not invoked.
      player.updateInventory();
    }
  }

  public void syncAllPlayers() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      apply(player);
    }
  }

  /**
   * Applies the latest state without replacing inventories that are temporarily unsafe to touch.
   */
  public void syncAllPlayersExcept(Set<UUID> excludedPlayers) {
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (!excludedPlayers.contains(player.getUniqueId())) {
        apply(player);
      }
    }
  }

  /**
   * Clears death-dropped shared equipment but preserves the shared Ender Chest, as vanilla does.
   */
  public void clearAndSyncAllPlayers() {
    storage = new ItemStack[STORAGE_SIZE];
    armor = new ItemStack[ARMOR_SIZE];
    offhand = null;
    initialized = true;
    Player storagePlayer = getStoragePlayer();
    if (storagePlayer != null) {
      storagePlayer.getInventory().setStorageContents(cloneItems(storage));
    } else {
      nativeStorage = null;
      nativeStorageOwner = null;
    }
    syncAllPlayers();
  }

  public void save() {
    Player storagePlayer = getStoragePlayer();
    if (storagePlayer != null) {
      capture(storagePlayer);
    }
    try (DataOutputStream output =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(inventoryFile)))) {
      output.writeInt(FILE_MAGIC);
      writeItems(output, storage);
      writeItems(output, armor);
      writeItems(output, new ItemStack[] {offhand});
      writeItems(output, enderChest);
    } catch (IOException exception) {
      plugin.getLogger().severe("Could not save shared inventory: " + exception.getMessage());
    }
  }

  public void load() {
    if (!inventoryFile.exists()) {
      if (legacyInventoryFile.exists()) {
        plugin
            .getLogger()
            .warning(
                "Legacy shared-inventory.dat was kept as a backup. "
                    + "The shared inventory will be rebuilt from the first player to join.");
      }
      return;
    }

    try (DataInputStream input =
        new DataInputStream(new BufferedInputStream(new FileInputStream(inventoryFile)))) {
      int magic = input.readInt();
      if (magic != FILE_MAGIC && magic != FILE_MAGIC_V1) {
        throw new IOException("Unrecognized shared inventory data.");
      }

      ItemStack[] loadedStorage = readItems(input);
      ItemStack[] loadedArmor = readItems(input);
      ItemStack[] loadedOffhand = readItems(input);
      ItemStack[] loadedEnderChest =
          magic == FILE_MAGIC ? readItems(input) : new ItemStack[ENDER_CHEST_SIZE];

      if (loadedStorage.length != STORAGE_SIZE
          || loadedArmor.length != ARMOR_SIZE
          || loadedOffhand.length != 1
          || loadedEnderChest.length != ENDER_CHEST_SIZE) {
        throw new IOException("Invalid shared inventory data.");
      }

      storage = cloneItems(loadedStorage);
      armor = cloneItems(loadedArmor);
      offhand = cloneItem(loadedOffhand[0]);
      enderChest = cloneItems(loadedEnderChest);
      initialized = true;
      plugin.getLogger().info("Shared inventory loaded successfully.");
    } catch (IOException exception) {
      plugin.getLogger().severe("Could not load shared inventory: " + exception.getMessage());
    }
  }

  private void writeItems(DataOutputStream output, ItemStack[] items) throws IOException {
    byte[] serializedItems = ItemStack.serializeItemsAsBytes(items);
    output.writeInt(serializedItems.length);
    output.write(serializedItems);
  }

  private ItemStack[] readItems(DataInputStream input) throws IOException {
    int byteCount = input.readInt();
    if (byteCount < 0 || byteCount > MAX_SECTION_BYTES) {
      throw new IOException("Invalid shared inventory section size.");
    }

    byte[] serializedItems = new byte[byteCount];
    input.readFully(serializedItems);
    return ItemStack.deserializeItemsFromBytes(serializedItems);
  }

  private static ItemStack[] cloneItems(ItemStack[] items) {
    ItemStack[] cloned = new ItemStack[items.length];
    for (int index = 0; index < items.length; index++) {
      cloned[index] = cloneItem(items[index]);
    }
    return cloned;
  }

  private static ItemStack cloneItem(ItemStack item) {
    return isEmpty(item) ? null : item.clone();
  }

  private ItemStack[] mergeChangedItems(
      ItemStack[] current, ItemStack[] baseline, ItemStack[] playerItems) {
    ItemStack[] merged = cloneItems(current);
    for (int index = 0; index < merged.length; index++) {
      if (!sameItem(baseline[index], playerItems[index])) {
        merged[index] = cloneItem(playerItems[index]);
      }
    }
    return merged;
  }

  private static boolean sameItem(ItemStack first, ItemStack second) {
    return isEmpty(first) && isEmpty(second) || Objects.equals(first, second);
  }

  private static boolean isEmpty(ItemStack item) {
    return item == null || item.getType().isAir() || item.getAmount() <= 0;
  }

  /**
   * Makes every linked player's native storage list point to one list, mirroring SharedInv's
   * reference-sharing model. Bukkit exposes no API for this, so the version-specific Paper bridge
   * is deliberately isolated here and falls back to normal API synchronization if unavailable.
   */
  private boolean shareNativeStorage(Player player) {
    if (nativeStorageUnavailable) {
      return false;
    }

    try {
      Object nativeInventory = getNativeInventory(player);
      Field itemsField = nativeInventory.getClass().getDeclaredField("items");
      itemsField.setAccessible(true);
      if (nativeStorage == null) {
        player.getInventory().setStorageContents(cloneItems(storage));
        nativeStorage = itemsField.get(nativeInventory);
        nativeStorageOwner = player.getUniqueId();
      } else {
        itemsField.set(nativeInventory, nativeStorage);
      }
      return true;
    } catch (ReflectiveOperationException | SecurityException exception) {
      nativeStorageUnavailable = true;
      plugin
          .getLogger()
          .warning(
              "Could not link Paper's native inventory storage; falling back to copy synchronization: "
                  + exception.getMessage());
      return false;
    }
  }

  private boolean isStorageShared() {
    return nativeStorage != null && !nativeStorageUnavailable;
  }

  private static Object getNativeInventory(Player player) throws ReflectiveOperationException {
    Method getInventory = player.getInventory().getClass().getMethod("getInventory");
    return getInventory.invoke(player.getInventory());
  }

  private Player getStoragePlayer() {
    if (nativeStorageOwner != null) {
      Player owner = Bukkit.getPlayer(nativeStorageOwner);
      if (owner != null && owner.isOnline()) {
        return owner;
      }
    }
    return Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
  }

  /** Immutable point-in-time copy of the shared inventory. */
  public static final class Snapshot {
    private final ItemStack[] storage;
    private final ItemStack[] armor;
    private final ItemStack offhand;
    private final ItemStack[] enderChest;

    private Snapshot(
        ItemStack[] storage, ItemStack[] armor, ItemStack offhand, ItemStack[] enderChest) {
      this.storage = cloneItems(storage);
      this.armor = cloneItems(armor);
      this.offhand = cloneItem(offhand);
      this.enderChest = cloneItems(enderChest);
    }
  }
}
