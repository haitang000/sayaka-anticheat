package cn.haitang.anticheat.check.world;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.BoundingBox;

/**
 * 方块交互距离检测（BlockReach / GhostHand）。
 *
 * 放置、破坏或右键使用方块时，眼睛到被点击方块表面的距离必须落在原版
 * 方块交互范围内（4.5 格；1.20.5+ 读取实体 attribute，兼容插件扩距）。
 * 表观距离按延迟与玩家近期移动放宽，明显超限的交互直接取消——
 * 隔墙放置 / 超远开箱 / 隔墙破坏在协议层就不可能，属于确定性的伪造交互包。
 */
public class BlockReachCheck extends Check {

    public BlockReachCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.BLOCK_REACH);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Block against = event.getBlockAgainst();
        if (against == null) return;
        evaluate(player, data(player), clickableShape(against), "放置", event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        evaluate(player, data(player), clickableShape(event.getBlock()), "破坏", event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        // 手持方块时这次点击会同时触发 BlockPlaceEvent，由 onPlace 统一判定，
        // 避免同一物理动作被计两次证据
        org.bukkit.inventory.ItemStack item = event.getItem();
        if (item != null && item.getType().isBlock()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        evaluate(player, data(player), clickableShape(block), "交互", event);
    }

    private void evaluate(Player player, PlayerData data, BoundingBox box, String verb,
                          org.bukkit.event.Cancellable event) {
        if (data.teleportedWithin(1000) || data.velocityWithin(750)) return;
        Location eye = player.getEyeLocation();
        double distance = InteractionMath.eyeToBoxDistance(eye, box);

        int ping = Math.min(player.getPing(), cfgI("max-ping-ms", 200));
        double movement = Math.min(
                data.getLastDeltaXZ() * cfgD("movement-compensation-multiplier", 0.45),
                cfgD("max-movement-compensation", 0.25));
        double threshold = InteractionMath.blockInteractionRange(player,
                cfgD("base-reach", 4.55))
                + ping / 1000.0 * cfgD("ping-compensation", 2.0)
                + movement;

        if (distance <= threshold) {
            data.buffer(type(), -0.5);
            return;
        }
        if (cfgB("cancel", true)
                && (distance > threshold + cfgD("cancel-margin", 0.3)
                || shouldMitigate(player))) {
            event.setCancelled(true);
        }
        double over = distance - threshold;
        double buffered = data.buffer(type(), 1.0 + Math.min(over * 2.0, 1.5));
        if (buffered >= cfgD("buffer-to-flag", 3.0)) {
            data.resetBuffer(type());
            flag(player, Math.min(2.5, 1.25 + over), String.format(
                    "%s距 %.2f格 > %.2f格 (ping=%dms, move+%.2f)",
                    verb, distance, threshold, ping, movement));
        }
    }

    static BoundingBox clickableShape(Block block) {
        return InteractionMath.clickableShape(block.getX(), block.getY(), block.getZ(),
                block.getCollisionShape().getBoundingBoxes());
    }
}
