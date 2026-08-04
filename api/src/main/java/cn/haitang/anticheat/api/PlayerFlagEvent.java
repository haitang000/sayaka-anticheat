package cn.haitang.anticheat.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 玩家未通过某项检测时触发（在 VL 累加与警报之前）。
 *
 * <p>监听示例：
 * <pre>{@code
 * @EventHandler(priority = EventPriority.MONITOR)
 * public void onFlag(PlayerFlagEvent event) {
 *     if (event.getCheckId().equals("KillAura")) { ... }
 * }
 * }</pre>
 *
 * <p>{@code setCancelled(true)} 可以否决本次违规：不累加 VL、不警报、不处罚。
 * 检测本身（如移动回弹、取消命中）已发生的仍会生效；如需完全禁检，请给玩家
 * 授予 {@code anticheat.bypass} 或注册 {@link SayakaApi#registerExemptionChecker}。
 */
public class PlayerFlagEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String checkId;
    private final String checkDisplay;
    /** 本次违规后该检测项将达到的 VL（若未被取消） */
    private final double vl;
    private final double weight;
    private final String detail;
    private boolean cancelled;

    public PlayerFlagEvent(Player player, String checkId, String checkDisplay,
                           double vl, double weight, String detail) {
        this.player = player;
        this.checkId = checkId;
        this.checkDisplay = checkDisplay;
        this.vl = vl;
        this.weight = weight;
        this.detail = detail;
    }

    public Player getPlayer() { return player; }

    /** 检测项 id，如 "Speed"、"KillAura"、"BadPackets"。 */
    public String getCheckId() { return checkId; }

    /** 检测项展示名，如 "移动速度异常 (Speed)"。 */
    public String getCheckDisplay() { return checkDisplay; }

    /** 本次违规后该检测项的 VL（未取消时的目标值）。 */
    public double getVl() { return vl; }

    /** 本次违规的权重增量。 */
    public double getWeight() { return weight; }

    /** 证据摘要（出现在警报与历史记录中）。 */
    public String getDetail() { return detail; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
