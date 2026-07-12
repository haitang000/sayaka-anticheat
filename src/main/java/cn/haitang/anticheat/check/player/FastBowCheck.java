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
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

/** 检测未达到正常蓄力时间却射出高力度箭矢的 FastBow。 */
public class FastBowCheck extends Check {

    public FastBowCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.FAST_BOW);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BOW) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        PlayerData data = data(player);
        if (!player.hasActiveItem() || data.getBowUseStartAt() == 0) {
            data.startBowUse();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getBow();
        if (bow == null || bow.getType() != Material.BOW) return;

        PlayerData data = data(player);
        long startedAt = data.getBowUseStartAt();
        data.clearBowUse();
        if (isExempt(player) || (data.isBedrock() && cfgB("exclude-bedrock", true))
                || startedAt == 0 || event.getForce() < cfgD("minimum-force", 0.95)) {
            return;
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        long minimum = cfgI("full-charge-min-ms", 850);
        if (elapsed < 0 || elapsed >= minimum) {
            data.buffer(type(), -0.75);
            return;
        }

        double buffered = data.buffer(type(), 1.0);
        if (buffered >= cfgD("buffer-to-flag", 2.0)) {
            data.resetBuffer(type());
            flag(player, 2.0, String.format("%.2f 力度仅蓄力 %dms",
                    event.getForce(), elapsed));
            if (cfgB("cancel", true) && shouldMitigate(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        data(event.getPlayer()).clearBowUse();
    }
}
