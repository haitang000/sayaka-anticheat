package cn.haitang.anticheat.data;

import cn.haitang.anticheat.check.CheckType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 单个在线玩家的全部运行时状态：各检测的 VL、缓冲值、移动轨迹、宽限时间戳等。
 * 仅存在于内存，玩家退出即销毁；跨会话数据（strike/封禁次数）见 {@link PersistentStore}。
 */
public class PlayerData {

    /** 一次违规记录（供 /sac history 查看） */
    public record ViolationRecord(long at, CheckType type, double vl, String detail) {}

    /** 一次已实际发送给玩家的递进警告，封禁时会写入处罚快照。 */
    public record WarningRecord(long at, CheckType type, int stage, double vl) {}

    /**
     * 移动采样：时间戳 + 该次移动的水平距离 + 该刻是否有地面支撑。
     * grounded 供 NoSlow 排除空中样本：空中水平惯性按空气阻力（0.91/tick）衰减，
     * 远慢于地面摩擦，用减速上限判定会误判跳跃/坠落中喝药水等合法移动。
     */
    public record MoveSample(long at, double dist, boolean grounded) {
        /** 兼容旧调用点：默认视为有地面支撑。 */
        public MoveSample(long at, double dist) {
            this(at, dist, true);
        }
    }

    private final UUID uuid;
    private final String name;

    /** 是否为基岩版（手机/主机）玩家，进服时解析一次，检测据此放宽/豁免 */
    private boolean bedrock;

    private final Map<CheckType, Double> vl = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Long> lastVlGainAt = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Double> buffer = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Long> lastAlertAt = new EnumMap<>(CheckType.class);
    private final Deque<ViolationRecord> recentViolations = new ArrayDeque<>();
    private final Deque<WarningRecord> recentWarnings = new ArrayDeque<>();

    // ---- 移动状态（由 MovementTracker 维护，检测类只读） ----
    private Location lastLocation;
    private Location lastValidLocation;
    private int airTicks;
    private double airStartY;
    private int flightAirTicks;
    private double flightStartY;
    private int hoverTicks;
    private double lastDeltaXZ;
    private double previousDeltaY;
    private double lastDeltaY;
    private Vector lastMovementDelta = new Vector();
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
    private Vector impulseVector;
    private double impulseProjection;
    private long impulseExpiresAt;
    private long impulseMinExpiresAt;
    private long serverLaunchExpiresAt;
    private boolean serverLaunchAirborne;
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
    private long lastCpsFlagAt;
    private long lastRoboticEvalAt;
    private int lastSwingTick = -1;
    private long lastTotemPopAt;
    private long kbPendingAt;

    /**
     * 视角历史采样：每个产生移动/转头事件的 tick 记录一次最终视角。
     * 玩家静止不转头时客户端不发包，也就没有新样本——此时视角保持不变，
     * {@link #rotationAtOrBefore} 取"该刻或更早的最近样本"语义仍然正确。
     */
    public record RotationSample(int tick, float yaw, float pitch) {}

    private static final int ROTATION_HISTORY_TICKS = 16;

    private final Deque<RotationSample> rotationHistory = new ArrayDeque<>();

    /** 追踪误差采样：一次命中的时间戳 + 攻击视角与目标中心的角度误差（度）。 */
    public record TrackSample(long at, double error) {}

    private final Deque<TrackSample> trackSamples = new ArrayDeque<>();
    private double lastTrackBearing = Double.NaN;

    // 同一服务端 tick 内绑定到真实攻击包的命中计数（KillAura burst）
    private int attackBurstTick = -1;
    private int attackBurstCount;
    private int attackBurstFlaggedTick = -1;

    /** 子判定线专用缓冲：与 {@link #buffer(CheckType, double)} 隔离，避免互相稀释。 */
    private final Map<String, Double> namedBuffers = new java.util.HashMap<>();

    // ---- 容器状态（InventoryMove） ----
    private boolean containerOpen;
    private long containerOpenAt;
    private long inventoryMoveIgnoreBefore;
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

    /** flying 包到达时间（毫秒）。Netty 线程写入、主线程 drain，其余字段均只在主线程访问 */
    private final Queue<Long> packetArrivals = new ConcurrentLinkedQueue<>();

    // ---- 挖掘状态 ----
    private String digKey;
    private long digStartAt;
    private int digExpectedTicks;
    private final Deque<Long> noDigBreaks = new ArrayDeque<>();

    // ---- 惩罚状态 ----
    public enum PunishmentState { IDLE, PENDING, COMMITTED }

    private PunishmentState punishmentState = PunishmentState.IDLE;
    private final Map<CheckType, Integer> punishmentWarnStages = new EnumMap<>(CheckType.class);
    private long lastPlayerWarnAt;
    private long lastSetbackAt;
    private int staticHoverCount;
    private int supportedTicks;

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

    /** 当前所有检测项 VL 的总和，仅用于管理界面展示。 */
    public double getTotalVl() {
        double total = 0;
        for (double value : vl.values()) total += value;
        return total;
    }

    public void resetVl(CheckType type) {
        vl.remove(type);
        lastVlGainAt.remove(type);
        buffer.remove(type);
        punishmentWarnStages.remove(type);
    }

    public void resetAllVl() {
        vl.clear();
        lastVlGainAt.clear();
        buffer.clear();
        punishmentWarnStages.clear();
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
        for (CheckType type : CheckType.values()) {
            if (getVl(type) < rewarnBelow) punishmentWarnStages.remove(type);
        }
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

    /** 命名子缓冲：同一检测项内多条独立判定线各自积累证据，互不稀释。 */
    public double buffer(String key, double delta) {
        double v = Math.max(0, namedBuffers.getOrDefault(key, 0.0) + delta);
        if (v == 0) namedBuffers.remove(key); else namedBuffers.put(key, v);
        return v;
    }

    public void resetBuffer(String key) {
        namedBuffers.remove(key);
    }

    public int getPunishmentWarnStage(CheckType type) {
        return punishmentWarnStages.getOrDefault(type, 0);
    }

    public void setPunishmentWarnStage(CheckType type, int stage) {
        if (stage <= 0) punishmentWarnStages.remove(type);
        else punishmentWarnStages.put(type, stage);
    }

    public void addViolation(ViolationRecord record) {
        recentViolations.addLast(record);
        while (recentViolations.size() > 40) recentViolations.removeFirst();
    }

    public Deque<ViolationRecord> getRecentViolations() {
        return recentViolations;
    }

    public void addWarning(WarningRecord record) {
        recentWarnings.addLast(record);
        while (recentWarnings.size() > 20) recentWarnings.removeFirst();
    }

    public Deque<WarningRecord> getRecentWarnings() {
        return recentWarnings;
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

    public int getFlightAirTicks() { return flightAirTicks; }
    public void setFlightAirTicks(int ticks) { this.flightAirTicks = Math.max(0, ticks); }

    public double getFlightStartY() { return flightStartY; }

    public int getHoverTicks() { return hoverTicks; }
    public void setHoverTicks(int hoverTicks) { this.hoverTicks = hoverTicks; }

    public double getLastDeltaXZ() { return lastDeltaXZ; }
    public void setLastDeltaXZ(double v) { this.lastDeltaXZ = v; }

    public double getLastDeltaY() { return lastDeltaY; }
    public void setLastDeltaY(double v) { this.lastDeltaY = v; }

    public double getPreviousDeltaY() { return previousDeltaY; }
    public void setPreviousDeltaY(double v) { this.previousDeltaY = v; }

    public Vector getLastMovementDelta() { return lastMovementDelta.clone(); }
    public void setLastMovementDelta(Vector movement) {
        this.lastMovementDelta = movement == null ? new Vector() : movement.clone();
    }

    public long getLastMoveAt() { return lastMoveAt; }
    public void setLastMoveAt(long lastMoveAt) { this.lastMoveAt = lastMoveAt; }

    public boolean isCollisionBelow() { return collisionBelow; }
    public void setCollisionBelow(boolean collisionBelow) { this.collisionBelow = collisionBelow; }

    public boolean isInWeb() { return inWeb; }
    public void setInWeb(boolean inWeb) { this.inWeb = inWeb; }

    public boolean isNearHoney() { return nearHoney; }
    public void setNearHoney(boolean nearHoney) { this.nearHoney = nearHoney; }

    public Deque<MoveSample> getSpeedWindow() { return speedWindow; }

    /** Restarts Flight evidence without changing shared airborne state used by other checks. */
    public void resetFlightTracking(double y) {
        this.flightAirTicks = 0;
        this.hoverTicks = 0;
        this.staticHoverCount = 0;
        this.flightStartY = y;
    }

    /** Restarts all airborne physics after landing, a world reset, or a setback. */
    public void resetAirborneState(double y) {
        this.airTicks = 0;
        this.airStartY = y;
        this.previousDeltaY = 0;
        this.lastDeltaY = 0;
        resetFlightTracking(y);
    }

    /** 世界切换 / 传送 / 重生后重置移动轨迹，避免跨位置误判 */
    public void resetMovement(Location current) {
        this.lastLocation = current;
        this.lastValidLocation = current;
        resetAirborneState(current == null ? 0 : current.getY());
        this.supportedTicks = 0;
        this.lastDeltaXZ = 0;
        this.lastMovementDelta = new Vector();
        this.collisionBelow = true;
        this.inWeb = false;
        this.nearHoney = false;
        this.speedWindow.clear();
        this.moveTimes.clear();
        this.rotationHistory.clear();
        clearImpulse();
        clearServerLaunch();
    }

    // ---- 宽限 ----

    public long getJoinAt() { return joinAt; }
    public void touchTeleport() { this.lastTeleportAt = System.currentTimeMillis(); }
    /** Records velocity actually issued by the server for both response and movement exemptions. */
    public void startImpulse(Vector velocity) {
        startImpulse(velocity, System.currentTimeMillis());
    }

    /**
     * Server-issued velocity keeps movement checks exempt for the estimated trajectory instead
     * of losing the exemption as soon as the first response is consumed.
     */
    void startImpulse(Vector velocity, long now) {
        if (!isFinite(velocity) || velocity.lengthSquared() < 1.0e-6) {
            clearImpulse();
            clearServerLaunch();
            return;
        }
        this.lastVelocityAt = now;
        this.lastVelocityMagnitude = velocity.length();
        this.impulseVector = velocity.clone();
        this.impulseProjection = 0.0;
        this.impulseMinExpiresAt = now + 250L;
        this.impulseExpiresAt = now + Math.min(2_000L,
                Math.max(450L, 600L + (long) (lastVelocityMagnitude * 450L)));
        this.serverLaunchExpiresAt = now + estimateServerLaunchMs(velocity);
        this.serverLaunchAirborne = false;
        this.speedWindow.clear();
    }

    public void consumeImpulse(Vector movement) {
        consumeImpulse(movement, System.currentTimeMillis());
    }

    void consumeImpulse(Vector movement, long now) {
        if (!hasActiveImpulse(now) || !isFinite(movement)) return;
        Vector direction = impulseVector.clone().normalize();
        impulseProjection += Math.max(0.0, movement.dot(direction));
        if (now >= impulseMinExpiresAt
                && impulseProjection >= Math.max(0.08, lastVelocityMagnitude * 0.8)) clearImpulse();
    }

    public boolean hasActiveImpulse() {
        return hasActiveImpulse(System.currentTimeMillis());
    }

    boolean hasActiveImpulse(long now) {
        if (impulseVector == null) return false;
        if (now <= impulseExpiresAt) return true;
        clearImpulse();
        return false;
    }

    public void clearImpulse() {
        impulseVector = null;
        impulseProjection = 0.0;
        impulseExpiresAt = 0L;
        impulseMinExpiresAt = 0L;
    }

    public boolean hasActiveServerLaunch() {
        return hasActiveServerLaunch(System.currentTimeMillis());
    }

    boolean hasActiveServerLaunch(long now) {
        return serverLaunchExpiresAt != 0L && now < serverLaunchExpiresAt;
    }

    /**
     * Updates the launch lifecycle from server-side collision state.
     *
     * @return true when a launch ended on this movement, so movement baselines can restart here
     */
    public boolean updateServerLaunch(boolean collisionBelow, long now) {
        if (serverLaunchExpiresAt == 0L) return false;
        if (!collisionBelow) serverLaunchAirborne = true;
        if (now < serverLaunchExpiresAt && (!serverLaunchAirborne || !collisionBelow)) return false;
        clearServerLaunch();
        return true;
    }

    private void clearServerLaunch() {
        serverLaunchExpiresAt = 0L;
        serverLaunchAirborne = false;
        speedWindow.clear();
    }

    /** Estimate the vanilla-style flight/slowdown time with one second of collision tolerance. */
    static long estimateServerLaunchMs(Vector velocity) {
        double verticalVelocity = Math.max(0.0, velocity.getY());
        double verticalPosition = 0.0;
        int verticalTicks = 0;
        while (verticalVelocity > 0.0 || verticalPosition > 0.0) {
            verticalPosition += verticalVelocity;
            verticalVelocity = (verticalVelocity - 0.08) * 0.98;
            verticalTicks++;
            if (verticalTicks >= 280) break;
        }

        double horizontalVelocity = Math.hypot(velocity.getX(), velocity.getZ());
        int horizontalTicks = 0;
        while (horizontalVelocity > 0.15 && horizontalTicks < 280) {
            horizontalVelocity *= 0.91;
            horizontalTicks++;
        }

        long estimate = (Math.max(verticalTicks, horizontalTicks) + 20L) * 50L;
        return Math.max(1_000L, Math.min(15_000L, estimate));
    }

    private static boolean isFinite(Vector vector) {
        return vector != null && Double.isFinite(vector.getX())
                && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }
    public void touchBounce() { this.lastBounceAt = System.currentTimeMillis(); }
    public void touchDamage() { this.lastDamageAt = System.currentTimeMillis(); }
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
    public boolean velocityWithin(long ms) {
        return velocityWithin(ms, System.currentTimeMillis());
    }

    boolean velocityWithin(long ms, long now) {
        return hasActiveServerLaunch(now)
                || (hasActiveImpulse(now) && within(lastVelocityAt, ms, now));
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
        return within(at, ms, System.currentTimeMillis());
    }

    private boolean within(long at, long ms, long now) {
        return at != 0 && now - at < ms;
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

    public long getLastCpsFlagAt() { return lastCpsFlagAt; }
    public void setLastCpsFlagAt(long v) { this.lastCpsFlagAt = v; }

    public long getLastRoboticEvalAt() { return lastRoboticEvalAt; }
    public void setLastRoboticEvalAt(long v) { this.lastRoboticEvalAt = v; }

    public int getLastSwingTick() { return lastSwingTick; }
    public void setLastSwingTick(int tick) { this.lastSwingTick = tick; }

    public long getLastTotemPopAt() { return lastTotemPopAt; }
    public void setLastTotemPopAt(long at) { this.lastTotemPopAt = at; }
    public void clearLastTotemPopAt() { this.lastTotemPopAt = 0; }

    public long getKbPendingAt() { return kbPendingAt; }
    public void setKbPendingAt(long at) { this.kbPendingAt = at; }

    /** 记录该 tick 的最终视角；同一 tick 重复采样只保留最后一次。 */
    public void addRotation(int tick, float yaw, float pitch) {
        RotationSample last = rotationHistory.peekLast();
        if (last != null && last.tick() == tick) rotationHistory.removeLast();
        rotationHistory.addLast(new RotationSample(tick, yaw, pitch));
        while (!rotationHistory.isEmpty()
                && tick - rotationHistory.peekFirst().tick() > ROTATION_HISTORY_TICKS) {
            rotationHistory.removeFirst();
        }
    }

    /** 该刻或更早的最近一次视角采样；历史为空返回 {@code null}。 */
    public RotationSample rotationAtOrBefore(int tick) {
        RotationSample best = null;
        for (RotationSample sample : rotationHistory) {
            if (sample.tick() <= tick) best = sample;
            else break;
        }
        return best;
    }

    public void clearRotationHistory() {
        rotationHistory.clear();
    }

    public Deque<TrackSample> getTrackSamples() { return trackSamples; }

    public double getLastTrackBearing() { return lastTrackBearing; }
    public void setLastTrackBearing(double bearing) { this.lastTrackBearing = bearing; }

    /**
     * 累计同一服务端 tick 内绑定真实攻击包的命中次数。
     *
     * @return 本次命中是该 tick 内的第几次（从 1 开始）
     */
    public int countAttackInTick(int tick) {
        if (attackBurstTick != tick) {
            attackBurstTick = tick;
            attackBurstCount = 0;
        }
        return ++attackBurstCount;
    }

    /** burst 每 tick 只上报一次：首次返回 true，同 tick 再次调用返回 false。 */
    public boolean markBurstFlagged(int tick) {
        if (attackBurstFlaggedTick == tick) return false;
        attackBurstFlaggedTick = tick;
        return true;
    }

    // ---- 容器 / 放置 / 移动包 ----

    public boolean isContainerOpen() { return containerOpen; }
    public long getContainerOpenAt() { return containerOpenAt; }
    public long getInventoryMoveIgnoreBefore() { return inventoryMoveIgnoreBefore; }
    public void setInventoryMoveIgnoreBefore(long at) { this.inventoryMoveIgnoreBefore = at; }

    public void setContainerOpen(boolean open) {
        this.containerOpen = open;
        if (open) {
            this.containerOpenAt = System.currentTimeMillis();
            this.inventoryMoveIgnoreBefore = containerOpenAt;
        } else {
            this.inventoryMoveIgnoreBefore = 0;
        }
    }

    public Deque<Long> getPlaceTimes() { return placeTimes; }
    public Deque<Long> getTowerTimes() { return towerTimes; }
    public Deque<Long> getMoveTimes() { return moveTimes; }
    public Queue<Long> getPacketArrivals() { return packetArrivals; }
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

    public boolean isPunishing() { return punishmentState != PunishmentState.IDLE; }
    public PunishmentState getPunishmentState() { return punishmentState; }
    public void setPunishmentState(PunishmentState state) { this.punishmentState = state; }
    public void setPunishing(boolean punishing) {
        this.punishmentState = punishing ? PunishmentState.PENDING : PunishmentState.IDLE;
    }

    public long getLastPlayerWarnAt() { return lastPlayerWarnAt; }
    public void setLastPlayerWarnAt(long v) { this.lastPlayerWarnAt = v; }

    public long getLastSetbackAt() { return lastSetbackAt; }
    public void touchSetback() { this.lastSetbackAt = System.currentTimeMillis(); }

    public int getStaticHoverCount() { return staticHoverCount; }
    public void setStaticHoverCount(int v) { this.staticHoverCount = v; }
    public int getSupportedTicks() { return supportedTicks; }
    public void setSupportedTicks(int value) { supportedTicks = Math.max(0, value); }

    public Map<CheckType, Long> getLastAlertAt() { return lastAlertAt; }

    public boolean hasExemptionCache(int tick) { return exemptionCacheTick == tick; }
    public boolean getExemptionCacheValue() { return exemptionCacheValue; }
    public void cacheExemption(int tick, boolean exempt) {
        this.exemptionCacheTick = tick;
        this.exemptionCacheValue = exempt;
    }
}
