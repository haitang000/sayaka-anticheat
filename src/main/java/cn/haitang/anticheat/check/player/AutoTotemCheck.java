package cn.haitang.anticheat.check.player;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 自动图腾检测（AutoTotem：图腾破碎后由宏/模组瞬间补装到副手）。
 *
 * 测量"图腾生效→副手重新出现图腾"的反应时间。对突发事件的人类反应
 * 下限约 150ms，作弊通常在 0~50ms 内完成；预判连按 F 偶然撞进阈值
 * 由缓冲吸收，连续两次以内不上报。
 */
public class AutoTotemCheck extends Check {

    /** 玩家自己背包界面里副手槽的 rawSlot 编号 */
    private static final int OFFHAND_RAW_SLOT = 45;

    public AutoTotemCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.AUTO_TOTEM);
    }

    /** 未持图腾时事件默认已取消，ignoreCancelled 保证只记录真实的图腾生效 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            data(player).setLastTotemPopAt(System.currentTimeMillis());
        }
    }

    /** 快捷键 F：把主手图腾换到副手 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        ItemStack off = event.getOffHandItem();
        if (off == null || off.getType() != Material.TOTEM_OF_UNDYING) return;
        evaluate(event.getPlayer(), "F键换手");
    }

    /** 背包点击：对着图腾按 F，或把光标图腾放进副手槽 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean toOffhand =
                (event.getClick() == ClickType.SWAP_OFFHAND
                        && event.getCurrentItem() != null
                        && event.getCurrentItem().getType() == Material.TOTEM_OF_UNDYING)
                || (event.getView().getType() == InventoryType.CRAFTING
                        && event.getRawSlot() == OFFHAND_RAW_SLOT
                        && event.getCursor() != null
                        && event.getCursor().getType() == Material.TOTEM_OF_UNDYING);
        if (toOffhand) evaluate(player, "背包补装");
    }

    private void evaluate(Player player, String how) {
        if (isExempt(player)) return;
        PlayerData data = data(player);
        long popAt = data.getLastTotemPopAt();
        if (popAt == 0) return;
        long elapsed = System.currentTimeMillis() - popAt;
        long minReact = cfgI("min-react-ms", 75);

        if (elapsed < minReact) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 2.0)) {
                data.resetBuffer(type());
                flag(player, 2.0, String.format("%s补图腾仅 %dms", how, elapsed));
            }
            data.clearLastTotemPopAt();
        } else {
            // 正常速度的补装：洗掉偶发的预判连按，并结束本次图腾破碎窗口
            data.buffer(type(), -0.5);
            data.clearLastTotemPopAt();
        }
    }
}
