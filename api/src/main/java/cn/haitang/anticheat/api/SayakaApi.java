package cn.haitang.anticheat.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Sayaka AntiCheat 对第三方插件暴露的服务接口。
 *
 * <p>通过 Bukkit ServicesManager 获取（paper 模块在 onEnable 时注册）：
 * <pre>{@code
 * SayakaApi api = Bukkit.getServicesManager().load(SayakaApi.class);
 * }</pre>
 *
 * <p>除了本接口的查询/管理方法，第三方插件还可以：
 * <ul>
 *   <li>监听 {@link PlayerFlagEvent} 实时获取违规（可取消以否决记录与处罚）；</li>
 *   <li>监听 {@link PlayerPunishEvent} 否决踢出/封禁；</li>
 *   <li>通过 {@link #registerExemptionChecker} 提供自定义豁免（如 VIP 飞行权限）。</li>
 * </ul>
 */
public interface SayakaApi {

    /** 一次违规记录（时间戳毫秒 + 检测项 id/展示名 + 该检测当前 VL + 证据摘要）。 */
    record ViolationInfo(long at, String checkId, String checkDisplay, double vl, String detail) {
    }

    /**
     * 玩家某一检测项的当前 VL。
     *
     * @param checkId 检测项 id（如 "Speed"、"KillAura"），见 {@code CheckType#byId}
     */
    double getVl(Player player, String checkId);

    /** 全部检测项 VL 的总和（仅展示用途，不参与处罚）。 */
    double getTotalVl(Player player);

    /** 该玩家当前是否被完全豁免（绕过权限 / 白名单等）。 */
    boolean isExempt(Player player);

    /** 该玩家是否在反作弊白名单中。 */
    boolean isWhitelisted(UUID playerId);

    /** 配置窗口内的 strike 计数（窗口小时数取插件配置）。 */
    int getStrikeCount(UUID playerId);

    /** 最近一次进服期间的违规明细（最多约 40 条）。 */
    List<ViolationInfo> getRecentViolations(Player player);

    /** 已注册并启用的检测项 id 列表。 */
    List<String> getCheckIds();

    /**
     * 清空玩家实时 VL。
     *
     * @param resetProfile true 时连同 strike / 封禁档案一并重置（等价于 /sac reset 玩家 all）
     */
    void resetPlayer(Player player, boolean resetProfile);

    /**
     * 注册自定义豁免判定器：任一判定器返回 true 时该玩家完全豁免检测
     * （与绕过权限、白名单同级）。
     *
     * <p>判定器会被主线程调用（每个玩家每 tick 至多一次，已按 tick 缓存），
     * 内部不要做耗时操作。
     *
     * @return 是否注册成功（重复注册返回 false）
     */
    boolean registerExemptionChecker(Predicate<Player> checker);

    /** 注销之前注册的豁免判定器。 */
    boolean unregisterExemptionChecker(Predicate<Player> checker);
}
