package cn.haitang.anticheat.check.player;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

/** 检测提前完成进食、喝药和饮用牛奶等物品使用动作。 */
public class FastUseCheck extends Check {

    public FastUseCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.FAST_USE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (!isConsumable(item)) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        PlayerData data = data(player);
        Material type = item.getType();
        // 持续使用期间可能收到重复交互事件，不允许它们刷新计时起点。
        if (!player.hasActiveItem() || data.getActiveUseType() != type
                || data.getActiveUseStartAt() == 0) {
            data.startItemUse(type);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        Material type = event.getItem().getType();
        long startedAt = data.getActiveUseStartAt();

        if (isExempt(player) || (data.isBedrock() && cfgB("exclude-bedrock", true))
                || startedAt == 0 || data.getActiveUseType() != type) {
            data.clearItemUse();
            return;
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        long minimum = minimumUseMs(type);
        data.clearItemUse();
        if (elapsed < 0 || elapsed >= minimum) {
            data.buffer(type(), -0.75);
            return;
        }

        double buffered = data.buffer(type(), 1.0);
        if (buffered >= cfgD("buffer-to-flag", 2.0)) {
            data.resetBuffer(type());
            flag(player, 2.0, String.format("%s 仅使用 %dms（下限 %dms）",
                    type.name(), elapsed, minimum));
            if (cfgB("cancel", true) && shouldMitigate(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        data(event.getPlayer()).clearItemUse();
    }

    private long minimumUseMs(Material type) {
        if (type == Material.DRIED_KELP) {
            return cfgI("fast-food-min-ms", 700);
        }
        return cfgI("default-min-ms", 1400);
    }

    private static boolean isConsumable(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        Material type = item.getType();
        return type.isEdible()
                || type == Material.POTION
                || type == Material.MILK_BUCKET
                || type == Material.HONEY_BOTTLE;
    }
}
