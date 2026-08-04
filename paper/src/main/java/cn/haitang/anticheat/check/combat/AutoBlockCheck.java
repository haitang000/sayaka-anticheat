package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

/**
 * 自动格挡攻击（AutoBlock）：格挡状态下的挥臂/命中。
 *
 * <p>1.9+ 战斗机制下，举起盾牌（isBlocking）时左键不会产生任何挥臂或攻击包，
 * 必须先松开右键才能攻击。因此"格挡状态下出现挥臂/命中"只可能来自
 * 同时按下左右键的作弊客户端——攻击不被打断、伤害照常打出。
 *
 * <p>松手与点击之间的同 tick 竞态由 buffer 吸收，需要连续多次证据才上报。
 */
public class AutoBlockCheck extends Check {

    private static final String BUFFER_KEY = "auto-block";

    public AutoBlockCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.AUTO_BLOCK);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        if (!player.isBlocking()) return;
        recordBlockWhileActing(player, "挥臂");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!player.isBlocking()) return;
        if (recordBlockWhileActing(player, "命中")) {
            event.setCancelled(true);
        }
    }

    /** 记录一次格挡状态下的战斗动作；返回 true 表示应取消本次命中。 */
    private boolean recordBlockWhileActing(Player player, String action) {
        if (isExempt(player)) return false;
        PlayerData data = data(player);
        if (data.isBedrock() && cfgB("exclude-bedrock", true)) return false;
        // 服务端停顿会把松手与点击的包在同刻排空，竞态窗口被放大
        if (serverLagging()) {
            data.resetBuffer(BUFFER_KEY);
            return false;
        }

        double buffered = data.buffer(BUFFER_KEY, 1.0);
        double threshold = Math.max(0.0, cfgD("buffer-to-flag", 2.5));
        if (!evidenceThresholdReached(buffered, threshold)) return false;

        data.resetBuffer(BUFFER_KEY);
        flag(player, Math.max(0.0, cfgD("flag-weight", 2.0)),
                String.format("格挡状态下连续 %d 次%s", (int) buffered, action));
        return true;
    }

    /** 纯判定：缓冲证据达到阈值才上报；阈值下限 1.0（配置写 0 仍至少需要一次证据） */
    static boolean evidenceThresholdReached(double buffered, double bufferToFlag) {
        return buffered >= Math.max(1.0, bufferToFlag);
    }
}
