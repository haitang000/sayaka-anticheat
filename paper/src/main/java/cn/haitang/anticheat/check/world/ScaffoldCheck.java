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
 * 非法搭路检测（Scaffold / Tower / FastPlace），四条判定线：
 * 1. 放置频率：任何放置持续超过每秒上限（原版按住右键 5/秒，快速点击也远低于上限）。
 * 2. 搭塔频率：空中垂直垫块超过原版跳跃节奏物理极限（≈1.7/秒）。
 * 3. 视角特征：在自己脚下延伸搭路必须低头（几何上俯视角至少 45°+），
 *    平视甚至抬头垫块是 Scaffold 挂最典型的特征。
 * 4. 不可见面：被点击面的外法线必须朝向眼睛（射线只能落在面向自己的面上），
 *    从方块"背面"放置只能来自伪造的放置包。眼睛位置滞后客户端 1-2 刻，
 *    留平面余量并按缓冲累积；仅对完整不透明方块判定（栅栏等轮廓小于
 *    整格的方块，其真实面平面不在整格边界上）。
 */
public class ScaffoldCheck extends Check {

    private static final String BUFFER_FACE = "scaffold.face";

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

        checkInvisibleFace(event, player, data, block);

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

    /** ---- 4. 不可见面：从被点击方块的背面放置 ---- */
    private void checkInvisibleFace(BlockPlaceEvent event, Player player,
                                    PlayerData data, Block placed) {
        if (!cfgB("face-check", true)) return;
        // 位置失配窗口（传送/击退）内眼睛坐标不可信
        if (data.teleportedWithin(1000) || data.velocityWithin(1000)) return;

        Block against = event.getBlockAgainst();
        // 只信整格不透明方块的面平面；替换放置（雪层/草丛）against==placed 时跳过
        if (against == null || !against.getType().isOccluding()) return;
        org.bukkit.block.BlockFace face = against.getFace(placed);
        if (face == null || Math.abs(face.getModX()) + Math.abs(face.getModY())
                + Math.abs(face.getModZ()) != 1) return;

        Location eye = player.getEyeLocation();
        double signedDistance = eyeToFacePlane(eye.getX(), eye.getY(), eye.getZ(),
                against.getX(), against.getY(), against.getZ(), face);
        double margin = cfgD("face-margin", 0.2);
        if (signedDistance < -margin) {
            double buffered = data.buffer(BUFFER_FACE, 1.0);
            if (buffered >= cfgD("face-buffer-to-flag", 2.0)) {
                data.resetBuffer(BUFFER_FACE);
                flag(player, 2.0, String.format("从 %s 面背侧 %.2f 格放置", face, -signedDistance));
                if (cfgB("cancel", true) && shouldMitigate(player)) event.setCancelled(true);
            }
        } else {
            data.buffer(BUFFER_FACE, -0.25);
        }
    }

    /**
     * 眼睛到被点击面平面的有符号距离：正值在面外侧（可见），负值在背侧。
     * 面法线只有一个非零分量，直接在该轴上算。
     */
    static double eyeToFacePlane(double eyeX, double eyeY, double eyeZ,
                                 int againstX, int againstY, int againstZ,
                                 org.bukkit.block.BlockFace face) {
        if (face.getModX() != 0) {
            double plane = againstX + (face.getModX() > 0 ? 1.0 : 0.0);
            return (eyeX - plane) * face.getModX();
        }
        if (face.getModY() != 0) {
            double plane = againstY + (face.getModY() > 0 ? 1.0 : 0.0);
            return (eyeY - plane) * face.getModY();
        }
        double plane = againstZ + (face.getModZ() > 0 ? 1.0 : 0.0);
        return (eyeZ - plane) * face.getModZ();
    }
}
