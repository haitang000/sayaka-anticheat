package cn.haitang.anticheat.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 检测项 VL 达到处罚阈值、即将执行踢出/临时封禁时触发（在处罚落地之前）。
 *
 * <p>{@code setCancelled(true)} 可以否决本次处罚：玩家不会被踢出/封禁，VL 保留。
 * 该事件在任何处罚路径（单服 / 群组网络）上都只触发一次。
 */
public class PlayerPunishEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String checkId;
    private final String checkDisplay;
    private final double vl;
    private boolean cancelled;

    public PlayerPunishEvent(Player player, String checkId, String checkDisplay, double vl) {
        this.player = player;
        this.checkId = checkId;
        this.checkDisplay = checkDisplay;
        this.vl = vl;
    }

    public Player getPlayer() { return player; }

    /** 触发处罚的检测项 id，如 "Timer"、"Reach"。 */
    public String getCheckId() { return checkId; }

    /** 检测项展示名，如 "移动包速率异常 (Timer)"。 */
    public String getCheckDisplay() { return checkDisplay; }

    /** 触发处罚时该检测项的 VL。 */
    public double getVl() { return vl; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
