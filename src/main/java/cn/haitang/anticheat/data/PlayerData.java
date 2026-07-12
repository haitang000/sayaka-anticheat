package cn.haitang.anticheat.data;

import cn.haitang.anticheat.check.CheckType;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单个在线玩家的全部运行时状态：各检测的 VL、缓冲值、移动轨迹、宽限时间戳等。
 * 仅存在于内存，玩家退出即销毁；跨会话数据（strike/封禁次数）见 {@link PersistentStore}。
 */
public class PlayerData {

    /** 一次违规记录（供 /sac history 查看） */
    public record ViolationRecord(long at, CheckType type, double vl, String detail) {}

    /** 移动采样：时间戳 + 该次移动的水平距离 */
    public record MoveSample(long at, double dist) {}

    private final UUID uuid;
    private final String name;

    /** 是否为基岩版（手机/主机）玩家，进服时解析一次，检测据此放宽/豁免 */
    private boolean bedrock;

    private final Map<CheckType, Double> vl = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Long> lastVlGainAt = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Double> buffer = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Long> lastAlertAt = new EnumMap<>(CheckType.class);
    private final Deque<ViolationRecord> recentViolations = new ArrayDeque<>();

    // ---- 移动状态（由 MovementTracker 维护，检测类只读） ----
    private Location lastLocation;
    private Location lastValidLocation;
    private int airTicks;
    private double airStartY;
    private int hoverTicks;
    private double lastDeltaXZ;
    private double lastDeltaY;
    private long lastMoveAt;
    private boolean collisionBelow = true;
    private boolean inWeb;
    private boolean nearHoney;
    private final Deque<MoveSample> speedWindow = new ArrayDeque<>();

    // ---- 宽限时间戳（毫秒，0 表示从未发生） ----
    private long joinAt;
    private long lastTeleportAt;
    private long lastDamageAt;
    private long lastVelocityAt;
    private double lastVelocityMagnitude;
    private long lastBounceAt;
    private long lastIceAt;
    private long lastSoulSandAt;
    private long lastLiquidAt;
    private long lastClimbAt;
    private long lastGlideAt;
    private long lastRiptideAt;
    private long lastLevitationAt;
    private long lastSlowFallAt;

    // ---- 战斗状态 ----
    private int lastHitTick = -1;
    private UUID lastHitTarget;
    private final Deque<Long> multiHits = new ArrayDeque<>();
    private final Deque<Long> clicks = new ArrayDeque<>();
    private long cpsSustainStart;
    private long lastRoboticEvalAt;
    private int lastSwingTick = -1;
    private long lastTotemPopAt;
    private long kbPendingAt;

    // ---- 容器状态（InventoryMove） ----
    private boolean containerOpen;
    private long containerOpenAt;
    private final Deque<Long> containerActionTimes = new ArrayDeque<>();

    // ---- 物品使用状态（FastUse / FastBow） ----
    private Material activeUseType;
    private long activeUseStartAt;
    private long bowUseStartAt;

    // ---- 方块放置（Scaffold） ----
    private final Deque<Long> placeTimes = new ArrayDeque<>();
    private final Deque<Long> towerTimes = new ArrayDeque<>();

    // ---- 移动包采样（Timer） ----
    private final Deque<Long> moveTimes = new ArrayDeque<>();

    // ---- 挖掘状态 ----
    private String digKey;
    private long digStartAt;
    private int digExpectedTicks;
    private final Deque<Long> noDigBreaks = new ArrayDeque<>();

    // ---- 惩罚状态 ----
    private boolean punishing;
    private int punishmentWarnStage;
    private long lastPlayerWarnAt;
    private long lastSetbackAt;
    private int staticHoverCount;

    // Generic exemption checks are identical for every check within one server tick.
    private int exemptionCacheTick = Integer.MIN_VALUE;
    private boolean exemptionCacheValue;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.joinAt = System.currentTimeMillis();
    }

    // ---- VL / 缓冲 ----

    public double addVl(CheckType type, double amount) {
        double v = vl.merge(type, amount, Double::sum);
        lastVlGainAt.put(type, System.currentTimeMillis());
        return v;
    }

    public double getVl(CheckType type) {
        return vl.getOrDefault(type, 0.0);
    }

    /** 当前所有检测项 VL 的总和，用于综合警告与处罚判定。 */
    public double getTotalVl() {
        double total = 0;
        for (double value : vl.values()) total += value;
        return total;
    }

    public void resetVl(CheckType type) {
        vl.remove(type);
        lastVlGainAt.remove(type);
        buffer.remove(type);
        punishmentWarnStage = 0;
    }

    public void resetAllVl() {
        vl.clear();
        lastVlGainAt.clear();
        buffer.clear();
        punishmentWarnStage = 0;
    }

    /**
     * 每秒调用：VL 衰减，且衰减到低位后允许重新触发警告。
     * 距该检测项最近一次违规不足 holdMs 时本轮跳过，持续触发的证据不会边累积边被稀释；
     * perTypeRate 中列出的检测项使用各自的衰减速率，未列出的用 perSecond。
     */
    public void decay(double perSecond, Map<CheckType, Double> perTypeRate,
                      long holdMs, double rewarnBelow) {
        long now = System.currentTimeMillis();
        for (CheckType t : CheckType.values()) {
            Double v = vl.get(t);
            if (v == null) continue;
            Long gainedAt = lastVlGainAt.get(t);
            if (gainedAt != null && now - gainedAt < holdMs) continue;
            double nv = v - perTypeRate.getOrDefault(t, perSecond);
            if (nv <= 0) {
                vl.remove(t);
                lastVlGainAt.remove(t);
            } else {
                vl.put(t, nv);
            }
        }
        if (getTotalVl() < rewarnBelow) punishmentWarnStage = 0;
    }

    /**
     * 调整缓冲值并返回调整后的结果。缓冲值不小于 0。
     * 检测项用它来吸收偶发抖动：连续异常才会攒满并触发违规。
     */
    public double buffer(CheckType type, double delta) {
        double v = Math.max(0, buffer.getOrDefault(type, 0.0) + delta);
        if (v == 0) buffer.remove(type); else buffer.put(type, v);
        return v;
    }

    public void resetBuffer(CheckType type) {
        buffer.remove(type);
    }

    public int getPunishmentWarnStage() {
        return punishmentWarnStage;
    }

    public void setPunishmentWarnStage(int stage) {
        punishmentWarnStage = stage;
    }

    public void addViolation(ViolationRecord record) {
        recentViolations.addLast(record);
        while (recentViolations.size() > 40) recentViolations.removeFirst();
    }

    public Deque<ViolationRecord> getRecentViolations() {
        return recentViolations;
    }

    public Map<CheckType, Double> getAllVl() {
        return vl;
    }

    // ---- getter / setter（移动） ----

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }

    public boolean isBedrock() { return bedrock; }
    public void setBedrock(boolean bedrock) { this.bedrock = bedrock; }

    public Location getLastLocation() { return lastLocation; }
    public void setLastLocation(Location loc) { this.lastLocation = loc; }

    public Location getLastValidLocation() { return lastValidLocation; }
    public void setLastValidLocation(Location loc) { this.lastValidLocation = loc; }

    public int getAirTicks() { return airTicks; }
    public void setAirTicks(int airTicks) { this.airTicks = airTicks; }

    public double getAirStartY() { return airStartY; }
    public void setAirStartY(double airStartY) { this.airStartY = airStartY; }

    public int getHoverTicks() { return hoverTicks; }
    public void setHoverTicks(int hoverTicks) { this.hoverTicks = hoverTicks; }

    public double getLastDeltaXZ() { return lastDeltaXZ; }
    public void setLastDeltaXZ(double v) { this.lastDeltaXZ = v; }

    public double getLastDeltaY() { return lastDeltaY; }
    public void setLastDeltaY(double v) { this.lastDeltaY = v; }

    public long getLastMoveAt() { return lastMoveAt; }
    public void setLastMoveAt(long lastMoveAt) { this.lastMoveAt = lastMoveAt; }

    public boolean isCollisionBelow() { return collisionBelow; }
    public void setCollisionBelow(boolean collisionBelow) { this.collisionBelow = collisionBelow; }

    public boolean isInWeb() { return inWeb; }
    public void setInWeb(boolean inWeb) { this.inWeb = inWeb; }

    public boolean isNearHoney() { return nearHoney; }
    public void setNearHoney(boolean nearHoney) { this.nearHoney = nearHoney; }

    public Deque<MoveSample> getSpeedWindow() { return speedWindow; }

    /** 世界切换 / 传送 / 重生后重置移动轨迹，避免跨位置误判 */
    public void resetMovement(Location current) {
        this.lastLocation = current;
        this.lastValidLocation = current;
        this.airTicks = 0;
        this.hoverTicks = 0;
        this.staticHoverCount = 0;
        this.lastDeltaXZ = 0;
        this.lastDeltaY = 0;
        this.collisionBelow = true;
        this.inWeb = false;
        this.nearHoney = false;
        this.airStartY = current == null ? 0 : current.getY();
        this.speedWindow.clear();
        this.moveTimes.clear();
    }

    // ---- 宽限 ----

    public long getJoinAt() { return joinAt; }
    public void touchTeleport() { this.lastTeleportAt = System.currentTimeMillis(); }
    public void touchDamage() { this.lastDamageAt = System.currentTimeMillis(); }

    /** 记录服务端赋速及其强度。强度越大（跳板/大炮/钩爪），后续合法惯性持续越久 */
    public void touchVelocity(double magnitude) {
        this.lastVelocityAt = System.currentTimeMillis();
        this.lastVelocityMagnitude = magnitude;
    }
    public void touchBounce() { this.lastBounceAt = System.currentTimeMillis(); }
    public void touchIce() { this.lastIceAt = System.currentTimeMillis(); }
    public void touchSoulSand() { this.lastSoulSandAt = System.currentTimeMillis(); }
    public void touchLiquid() { this.lastLiquidAt = System.currentTimeMillis(); }
    public void touchClimb() { this.lastClimbAt = System.currentTimeMillis(); }
    public void touchGlide() { this.lastGlideAt = System.currentTimeMillis(); }
    public void touchRiptide() { this.lastRiptideAt = System.currentTimeMillis(); }
    public void touchLevitation() { this.lastLevitationAt = System.currentTimeMillis(); }
    public void touchSlowFall() { this.lastSlowFallAt = System.currentTimeMillis(); }

    public boolean teleportedWithin(long ms) { return within(lastTeleportAt, ms); }
    public boolean damagedWithin(long ms) { return within(lastDamageAt, ms); }

    /** 赋速宽限窗口按赋速强度放大（普通击退≈0.55，插件发射可达 3+），上限 4 倍 */
    public boolean velocityWithin(long ms) {
        double scale = Math.min(4.0, 1.0 + lastVelocityMagnitude);
        return within(lastVelocityAt, (long) (ms * scale));
    }

    public long getLastVelocityAt() { return lastVelocityAt; }
    public boolean bouncedWithin(long ms) { return within(lastBounceAt, ms); }
    public boolean iceWithin(long ms) { return within(lastIceAt, ms); }
    public boolean soulSandWithin(long ms) { return within(lastSoulSandAt, ms); }
    public boolean liquidWithin(long ms) { return within(lastLiquidAt, ms); }
    public boolean climbedWithin(long ms) { return within(lastClimbAt, ms); }
    public boolean glidedWithin(long ms) { return within(lastGlideAt, ms); }
    public boolean riptideWithin(long ms) { return within(lastRiptideAt, ms); }
    public boolean levitationWithin(long ms) { return within(lastLevitationAt, ms); }
    public boolean slowFallWithin(long ms) { return within(lastSlowFallAt, ms); }

    private boolean within(long at, long ms) {
        return at != 0 && System.currentTimeMillis() - at < ms;
    }

    // ---- 战斗 ----

    public int getLastHitTick() { return lastHitTick; }
    public void setLastHitTick(int tick) { this.lastHitTick = tick; }

    public UUID getLastHitTarget() { return lastHitTarget; }
    public void setLastHitTarget(UUID target) { this.lastHitTarget = target; }

    public Deque<Long> getMultiHits() { return multiHits; }
    public Deque<Long> getClicks() { return clicks; }

    public long getCpsSustainStart() { return cpsSustainStart; }
    public void setCpsSustainStart(long v) { this.cpsSustainStart = v; }

    public long getLastRoboticEvalAt() { return lastRoboticEvalAt; }
    public void setLastRoboticEvalAt(long v) { this.lastRoboticEvalAt = v; }

    public int getLastSwingTick() { return lastSwingTick; }
    public void setLastSwingTick(int tick) { this.lastSwingTick = tick; }

    public long getLastTotemPopAt() { return lastTotemPopAt; }
    public void setLastTotemPopAt(long at) { this.lastTotemPopAt = at; }
    public void clearLastTotemPopAt() { this.lastTotemPopAt = 0; }

    public long getKbPendingAt() { return kbPendingAt; }
    public void setKbPendingAt(long at) { this.kbPendingAt = at; }

    // ---- 容器 / 放置 / 移动包 ----

    public boolean isContainerOpen() { return containerOpen; }
    public long getContainerOpenAt() { return containerOpenAt; }

    public void setContainerOpen(boolean open) {
        this.containerOpen = open;
        if (open) this.containerOpenAt = System.currentTimeMillis();
    }

    public Deque<Long> getPlaceTimes() { return placeTimes; }
    public Deque<Long> getTowerTimes() { return towerTimes; }
    public Deque<Long> getMoveTimes() { return moveTimes; }
    public Deque<Long> getContainerActionTimes() { return containerActionTimes; }

    // ---- 物品使用 ----

    public Material getActiveUseType() { return activeUseType; }
    public long getActiveUseStartAt() { return activeUseStartAt; }

    public void startItemUse(Material type) {
        this.activeUseType = type;
        this.activeUseStartAt = System.currentTimeMillis();
    }

    public void clearItemUse() {
        this.activeUseType = null;
        this.activeUseStartAt = 0;
    }

    public long getBowUseStartAt() { return bowUseStartAt; }
    public void startBowUse() { this.bowUseStartAt = System.currentTimeMillis(); }
    public void clearBowUse() { this.bowUseStartAt = 0; }

    // ---- 挖掘 ----

    public String getDigKey() { return digKey; }
    public long getDigStartAt() { return digStartAt; }
    public int getDigExpectedTicks() { return digExpectedTicks; }

    public void startDig(String key, int expectedTicks) {
        this.digKey = key;
        this.digStartAt = System.currentTimeMillis();
        this.digExpectedTicks = expectedTicks;
    }

    public void clearDig() {
        this.digKey = null;
        this.digStartAt = 0;
        this.digExpectedTicks = 0;
    }

    public Deque<Long> getNoDigBreaks() { return noDigBreaks; }

    // ---- 惩罚 ----

    public boolean isPunishing() { return punishing; }
    public void setPunishing(boolean punishing) { this.punishing = punishing; }

    public long getLastPlayerWarnAt() { return lastPlayerWarnAt; }
    public void setLastPlayerWarnAt(long v) { this.lastPlayerWarnAt = v; }

    public long getLastSetbackAt() { return lastSetbackAt; }
    public void touchSetback() { this.lastSetbackAt = System.currentTimeMillis(); }

    public int getStaticHoverCount() { return staticHoverCount; }
    public void setStaticHoverCount(int v) { this.staticHoverCount = v; }

    public Map<CheckType, Long> getLastAlertAt() { return lastAlertAt; }

    public boolean hasExemptionCache(int tick) { return exemptionCacheTick == tick; }
    public boolean getExemptionCacheValue() { return exemptionCacheValue; }
    public void cacheExemption(int tick, boolean exempt) {
        this.exemptionCacheTick = tick;
        this.exemptionCacheValue = exempt;
    }
}
