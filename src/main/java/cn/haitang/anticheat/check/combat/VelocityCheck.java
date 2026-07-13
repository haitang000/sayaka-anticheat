package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 拒绝击退检测（Velocity/AntiKnockback：客户端无视服务端下发的击退速度）。
 *
 * 两段式判定，只信"真正发给客户端的东西"：
 * 1. 受击（豁免场景外）先标记待验证；
 * 2. 同刻的 PlayerVelocityEvent 真正下发击退速度时才武装测量——
 *    决斗/区域保护类插件取消或削弱击退（竞技场无击退等）不会走到这一步，
 *    从根上杜绝"插件吞了击退却怪玩家不动"的误判。
 * 武装后第 4 刻测量位移；期间若又出现任何新的赋速事件（技能位移、
 * 二段击退修改插件）说明物理已不可预测，测量作废。
 * 贴墙、液体、攀爬、蛛网、格挡、载具、无敌帧连击等场景全部跳过，
 * 只对"开阔地吃到足量击退却纹丝不动"的典型 AntiKB 累积违规。
 */
public class VelocityCheck extends Check {

    /** 击退速度下发后延迟多少刻测量位移 */
    private static final int MEASURE_DELAY_TICKS = 4;

    /** 受击标记的有效期：击退速度事件与伤害事件同刻产生，超时即失效 */
    private static final long PENDING_VALID_MS = 150;

    /** 受击前移动样本超过该时长后，不再用于估算玩家的主动移动。 */
    private static final long MOVEMENT_SAMPLE_VALID_MS = 150;

    public VelocityCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.VELOCITY);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                && cause != EntityDamageEvent.DamageCause.PROJECTILE) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getFinalDamage() <= 0) return;
        if (isExempt(victim)) return;
        if (victim.isDead() || victim.isInsideVehicle() || victim.isBlocking()) return;
        if (victim.isGliding() || victim.isRiptiding()) return;
        // 无敌帧内的追加伤害不产生击退
        if (victim.getNoDamageTicks() > 10) return;
        if (MoveUtil.isClimbing(victim) || MoveUtil.isInWeb(victim)
                || MoveUtil.touchingLiquid(victim)) return;

        data(victim).setKbPendingAt(System.currentTimeMillis());
    }

    /** 击退速度真正下发时武装测量；被插件取消的事件（ignoreCancelled）不会到达这里 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        PlayerData data = data(victim);
        long pending = data.getKbPendingAt();
        if (pending == 0) return;
        data.setKbPendingAt(0);
        if (System.currentTimeMillis() - pending > PENDING_VALID_MS) return;
        // 插件把击退改小（老版本战斗手感、无击退竞技场等）也不测
        if (event.getVelocity().length() < cfgD("min-kb-velocity", 0.25)) return;

        Location before = victim.getLocation().clone();
        // ConnectionListener 已先记录本次赋速；测量时若时间戳变化说明又有新赋速
        long velocityStamp = data.getLastVelocityAt();
        long armedAt = System.nanoTime();
        Vector expected = event.getVelocity().clone();
        long movementAge = System.currentTimeMillis() - data.getLastMoveAt();
        Vector priorMovement = movementAge >= 0 && movementAge <= MOVEMENT_SAMPLE_VALID_MS
                ? data.getLastMovementDelta() : new Vector();
        UUID id = victim.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> measure(id, before, velocityStamp, armedAt, expected, priorMovement),
                MEASURE_DELAY_TICKS);
    }

    private void measure(UUID id, Location before, long velocityStamp,
                         long armedAt, Vector expected, Vector priorMovement) {
        Player victim = plugin.getServer().getPlayer(id);
        if (victim == null || !victim.isOnline() || victim.isDead()) return;
        PlayerData data = data(victim);
        if (data.getLastVelocityAt() != velocityStamp) return; // 期间出现新赋速，作废
        if (data.teleportedWithin(1000) || victim.isInsideVehicle()) return;

        Location now = victim.getLocation();
        if (now.getWorld() == null || !now.getWorld().equals(before.getWorld())) return;
        if (MoveUtil.touchingLiquid(victim) || MoveUtil.isClimbing(victim)
                || MoveUtil.isInWeb(victim)) return;
        if (nearWall(now) || nearWall(before)) return;

        Vector displacement = now.toVector().subtract(before.toVector());
        double projection = responseProjection(displacement, expected, priorMovement,
                MEASURE_DELAY_TICKS);
        double required = Math.max(cfgD("min-displacement", 0.05),
                expected.length() * cfgD("min-response-ratio", 0.15));
        if (plugin.getPacketTimeline() != null
                && !plugin.getPacketTimeline().wasImpulseSent(id, armedAt, expected)) return;

        if (projection < required) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 3.0)) {
                data.resetBuffer(type());
                flag(victim, 2.0, String.format("受击 %d 刻沿击退方向仅响应 %.3f/%.3f 格",
                        MEASURE_DELAY_TICKS, projection, required));
            }
        } else {
            data.buffer(type(), -1.0);
        }
    }

    /**
     * 从净位移中移除受击前移动趋势的影响。玩家迎着击退方向持续前进时，
     * 客户端正确应用击退后的净位移也可能很小，但相对其原有移动趋势仍有明显响应。
     */
    static double responseProjection(Vector displacement, Vector expected,
                                     Vector priorMovement, int measuredTicks) {
        if (!isFinite(displacement) || !isFinite(expected)
                || expected.lengthSquared() < 1.0e-9) return 0.0;

        Vector direction = expected.clone().normalize();
        double projected = displacement.dot(direction);
        if (isFinite(priorMovement) && measuredTicks > 0) {
            Vector horizontalMovement = priorMovement.clone().setY(0.0);
            double opposingPerTick = Math.max(0.0, -horizontalMovement.dot(direction));
            projected += opposingPerTick * measuredTicks;
        }
        return Math.max(0.0, projected);
    }

    private static boolean isFinite(Vector vector) {
        return vector != null && Double.isFinite(vector.getX())
                && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }

    /** 玩家周边一圈是否有实体方块（贴墙受击可以完全不位移，跳过判定） */
    private boolean nearWall(Location loc) {
        World world = loc.getWorld();
        if (world == null) return true;
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    if (world.getBlockAt(bx + dx, by + dy, bz + dz).getType().isSolid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
