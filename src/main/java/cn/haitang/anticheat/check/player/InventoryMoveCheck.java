package cn.haitang.anticheat.check.player;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 开容器移动检测（InventoryMove：打开箱子等界面时照常走动）。
 *
 * 原版客户端打开容器界面后不再响应移动按键，只剩惯性滑行和坠落。
 * 打开界面并过了宽限期后，1.5 秒滚动窗口内仍有明显水平位移即违规。
 * 惯性、冰面、液体、击退、载具、插件在移动中弹出的菜单均已豁免。
 * 玩家自己的背包（E 键）不触发服务端事件，不在检测范围内。
 */
public class InventoryMoveCheck extends Check {

    public InventoryMoveCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.INVENTORY_MOVE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.CREATIVE
                || type == InventoryType.PLAYER) return;
        data(player).setContainerOpen(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            data(player).setContainerOpen(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getTo() == null || isExempt(player)) return;
        PlayerData data = data(player);
        if (!data.isContainerOpen()) return;

        int graceMs = Math.max(0, cfgI("grace-ms", 500));
        if (shouldSkipForPing(player.getPing(), cfgI("max-ping-ms", 200))) {
            data.setInventoryMoveIgnoreBefore(System.currentTimeMillis() + graceMs);
            data.buffer(type(), -1.0);
            return;
        }

        if (player.isInsideVehicle() || player.isGliding()) return;
        if (data.velocityWithin(2000) || data.teleportedWithin(1500)) return;
        if (data.liquidWithin(1200) || data.iceWithin(2500) || data.bouncedWithin(2000)) return;

        long graceEnd = Math.max(
                data.getContainerOpenAt() + graceMs,
                data.getInventoryMoveIgnoreBefore());
        long now = System.currentTimeMillis();
        if (now < graceEnd) return;

        // 只统计宽限期之后的位移采样
        double dist = 0;
        for (PlayerData.MoveSample sample : data.getSpeedWindow()) {
            if (sample.at() >= graceEnd) dist += sample.dist();
        }
        if (dist > cfgD("max-distance", 2.0)) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 6.0)) {
                data.resetBuffer(type());
                flag(player, 1.5, String.format("开界面移动 %.1f 格", dist));
            }
        } else {
            data.buffer(type(), -1.0);
        }
    }

    static boolean shouldSkipForPing(int ping, int maxPingMs) {
        return maxPingMs > 0 && ping >= maxPingMs;
    }
}
