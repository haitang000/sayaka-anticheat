package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 虚假暴击检测（Criticals：伪造 onGround/摔落状态，站在地面打出跳跃暴击）。
 *
 * 服务端按客户端声明判定暴击，但本插件的移动轨迹是按坐标独立推算的：
 * 暴击成立却贴地（0.05 格严格判定）即为伪造。合法跳跃暴击命中时
 * 攻击者必然离地——攻击包先于落地移动包到达，落地瞬间也不会误判。
 */
public class CriticalsCheck extends Check {

    public CriticalsCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.CRITICALS);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!event.isCritical()) return;
        if (isExempt(attacker)) return;
        PlayerData data = data(attacker);

        if (attacker.isInsideVehicle()) return;
        if (data.teleportedWithin(1000) || data.velocityWithin(1000)) return;
        if (data.bouncedWithin(2000) || data.climbedWithin(1000)) return;
        if (MoveUtil.isInWeb(attacker)) return;

        boolean grounded = data.getAirTicks() == 0
                && MoveUtil.hasCollisionBelow(attacker.getLocation(), 0.05);
        if (!grounded) {
            data.buffer(type(), -0.5);
            return;
        }

        double buffered = data.buffer(type(), 1.0);
        if (buffered >= cfgD("buffer-to-flag", 2.0)) {
            data.resetBuffer(type());
            flag(attacker, 2.0, "站在地面打出暴击");
            if (cfgB("cancel-hits", true) && shouldMitigate(attacker)) {
                event.setCancelled(true);
            }
        }
    }
}
