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
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * 非法搭路检测（Scaffold / Tower / FastPlace），三条判定线：
 * 1. 放置频率：任何放置持续超过每秒上限（原版按住右键 5/秒，快速点击也远低于上限）。
 * 2. 搭塔频率：空中垂直垫块超过原版跳跃节奏物理极限（≈1.7/秒）。
 * 3. 视角特征：在自己脚下延伸搭路必须低头（几何上俯视角至少 45°+），
 *    平视甚至抬头垫块是 Scaffold 挂最典型的特征。
 */
public class ScaffoldCheck extends Check {

    public ScaffoldCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.SCAFFOLD);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (player.isFlying() || player.getAllowFlight()) return;
        PlayerData data = data(player);
        long now = System.currentTimeMillis();
        Block block = event.getBlockPlaced();
        Location loc = player.getLocation();

        // 建筑法杖/技能类插件会以玩家名义在同一瞬间放置一批方块，
        // 同批只计第一个，其余不参与任何判定（真人和挂都做不到 25ms 内两次放置）
        var places = data.getPlaceTimes();
        if (!places.isEmpty() && now - places.peekLast() < 25) return;

        // ---- 1. 放置频率（FastPlace，2 秒滚动窗口） ----
        places.addLast(now);
        while (!places.isEmpty() && now - places.peekFirst() > 2000) places.removeFirst();
        int maxPerSec = cfgI("max-place-per-second", 14);
        if (places.size() > maxPerSec * 2) {
            places.clear();
            flag(player, 1.5, String.format("放置频率 > %d/秒", maxPerSec));
            if (cfgB("cancel", true) && shouldMitigate(player)) event.setCancelled(true);
            return;
        }

        // 后两条只分析"垫在自己脚下延伸路径"的放置
        if (block.getY() != loc.getBlockY() - 1) return;
        double dxz = Math.hypot(block.getX() + 0.5 - loc.getX(), block.getZ() + 0.5 - loc.getZ());
        if (dxz > 1.8) return;

        // ---- 2. 搭塔频率（Tower：空中垂直垫块） ----
        if (data.getAirTicks() > 0
                && block.getX() == loc.getBlockX() && block.getZ() == loc.getBlockZ()) {
            var tower = data.getTowerTimes();
            tower.addLast(now);
            while (!tower.isEmpty() && now - tower.peekFirst() > 2000) tower.removeFirst();
            double maxTower = cfgD("tower-max-per-second", 2.6);
            if (tower.size() > maxTower * 2) {
                tower.clear();
                flag(player, 2.0, "垂直垫块超过跳跃节奏极限");
                if (cfgB("cancel", true) && shouldMitigate(player)) event.setCancelled(true);
                return;
            }
        }

        // ---- 3. 视角特征：延伸搭路却不低头 ----
        float pitch = loc.getPitch();
        boolean moving = data.getLastDeltaXZ() > 0.08 || data.getAirTicks() > 0;
        if (moving && pitch < cfgD("min-pitch", 35.0)) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 3.0)) {
                data.resetBuffer(type());
                flag(player, 1.5, String.format("俯视角仅 %.0f° 却在脚下垫块", pitch));
                if (cfgB("cancel", true) && shouldMitigate(player)) event.setCancelled(true);
            }
        } else {
            data.buffer(type(), -0.5);
        }
    }
}
