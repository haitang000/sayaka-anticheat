package cn.haitang.anticheat.check.player;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;

/** 检测在真实容器中以超人类频率连续取出物品的 ChestStealer。 */
public class ChestStealerCheck extends Check {

    private static final EnumSet<InventoryAction> TAKE_ACTIONS = EnumSet.of(
            InventoryAction.PICKUP_ALL,
            InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE,
            InventoryAction.PICKUP_SOME,
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
            InventoryAction.HOTBAR_SWAP,
            InventoryAction.HOTBAR_MOVE_AND_READD,
            InventoryAction.COLLECT_TO_CURSOR,
            InventoryAction.SWAP_WITH_CURSOR
    );

    public ChestStealerCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.CHEST_STEALER);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() == GameMode.CREATIVE || isExempt(player)) return;
        PlayerData data = data(player);
        if (data.isBedrock() && cfgB("exclude-bedrock", true)) return;

        Inventory top = event.getView().getTopInventory();
        if (!isRealContainer(top)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        if (!TAKE_ACTIONS.contains(event.getAction())) return;
        ItemStack current = event.getCurrentItem();
        if (current == null || current.isEmpty()) return;

        long now = System.currentTimeMillis();
        var actions = data.getContainerActionTimes();
        actions.addLast(now);
        int windowMs = Math.max(250, cfgI("window-ms", 1000));
        while (!actions.isEmpty() && now - actions.peekFirst() > windowMs) {
            actions.removeFirst();
        }

        int maximum = Math.max(2, cfgI("max-actions-per-window", 12));
        if (actions.size() <= maximum) return;

        long span = now - actions.peekFirst();
        actions.clear();
        double buffered = data.buffer(type(), 1.0);
        if (buffered >= cfgD("buffer-to-flag", 2.0)) {
            data.resetBuffer(type());
            flag(player, 1.5, String.format("%dms 内取出超过 %d 组物品", span, maximum));
            if (cfgB("cancel", true) && shouldMitigate(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            data(player).getContainerActionTimes().clear();
        }
    }

    private static boolean isRealContainer(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof Container
                || holder instanceof DoubleChest
                || holder instanceof StorageMinecart
                || inventory.getType() == InventoryType.ENDER_CHEST;
    }
}
