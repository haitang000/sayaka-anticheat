package cn.haitang.anticheat.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基岩版（手机 / 平板 / 主机）玩家识别。
 *
 * 基岩玩家一般通过 Geyser + Floodgate 接入 Java 服务端，其触屏瞄准、自动跳跃、
 * 代理转发的移动包与 Java 端物理存在天然差异，会让部分检测频繁误报。
 * 本类用于把这类玩家识别出来，交由各检测放宽或豁免（见 config 的 settings.bedrock）。
 *
 * <p>为保持插件"无外部依赖"：
 * <ul>
 *   <li>优先反射调用 Floodgate API（存在才用，缺失不报错）；</li>
 *   <li>反射不可用时回退到 Floodgate 的 UUID 方案——基岩玩家 UUID 高 64 位恒为 0
 *       （{@code new UUID(0, xuid)}），Java 正版玩家的随机 UUID 命中此值的概率可忽略。</li>
 * </ul>
 */
public final class BedrockSupport {

    private final boolean floodgatePresent;
    private Object floodgateApi;
    private Method isFloodgatePlayer;

    public BedrockSupport(Logger logger) {
        boolean present = Bukkit.getPluginManager().getPlugin("floodgate") != null
                || Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null;
        this.floodgatePresent = present;

        if (present) {
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                this.floodgateApi = apiClass.getMethod("getInstance").invoke(null);
                this.isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            } catch (Throwable t) {
                // Floodgate 存在但 API 版本不匹配：退回 UUID 方案，仅记一次日志
                logger.log(Level.FINE, "Floodgate API 反射不可用，改用 UUID 方案识别基岩玩家", t);
                this.floodgateApi = null;
                this.isFloodgatePlayer = null;
            }
            logger.info("检测到 Floodgate/Geyser，已启用基岩版玩家兼容识别");
        }
    }

    /** 服务器是否接入了基岩互通（据此决定是否需要基岩兼容逻辑）。 */
    public boolean isPresent() {
        return floodgatePresent;
    }

    /** 该玩家是否为基岩版（手机/主机）玩家。未接入互通时恒为 false。 */
    public boolean isBedrock(Player player) {
        if (!floodgatePresent || player == null) return false;
        if (floodgateApi != null && isFloodgatePlayer != null) {
            try {
                Object r = isFloodgatePlayer.invoke(floodgateApi, player.getUniqueId());
                if (r instanceof Boolean b) return b;
            } catch (Throwable ignored) {
                // 单次反射失败不致命，落到 UUID 方案
            }
        }
        return player.getUniqueId().getMostSignificantBits() == 0L;
    }
}
