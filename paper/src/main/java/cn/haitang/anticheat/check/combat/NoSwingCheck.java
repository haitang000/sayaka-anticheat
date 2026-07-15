package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;

import java.util.UUID;

/**
 * 无挥臂攻击检测（NoSwing：作弊客户端攻击时省略挥臂动画包）。
 *
 * 原版客户端每次攻击都伴随挥臂包，两者同刻先后到达且顺序不定，
 * 因此延后 2 刻再验证"攻击刻之后是否出现过挥臂"，
 * 慢速单点、先攻击后挥臂等正常时序都不会误判。
 */
public class NoSwingCheck extends Check {

    public NoSwingCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.NO_SWING);
    }

    /** 任何挥臂（攻击/挖掘/空挥）都刷新记录，不筛类型，取宽松方向 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnimation(PlayerAnimationEvent event) {
        data(event.getPlayer()).setLastSwingTick(Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (isExempt(attacker)) return;

        int attackTick = Bukkit.getCurrentTick();
        CombatAttackContext.Attack attack = plugin.getCombatAttackContext().attack(event).orElse(null);
        if (attack == null) return;

        if (cfgB("cancel-hits", true) && shouldMitigate(attacker)) {
            event.setCancelled(true);
        }

        UUID id = attacker.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) return;
            PlayerData data = data(player);
            boolean packetSwing = attack.packetBacked() && plugin.getPacketTimeline()
                    .hasSwingNear(id, attack.packetSequence(), attack.receivedNanos(),
                            attack.precedingSwingSequence());
            if (packetSwing || (!attack.packetBacked() && data.getLastSwingTick() >= attackTick)) {
                data.buffer(type(), -0.75);
                return;
            }
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 2.0)) {
                data.resetBuffer(type());
                flag(player, 2.0, "攻击未伴随挥臂动画");
            }
        }, 2L);
    }

}
