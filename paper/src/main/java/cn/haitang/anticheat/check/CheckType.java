package cn.haitang.anticheat.check;

/**
 * 所有检测项。configKey 对应 config.yml 中 checks.<key> 配置段。
 */
public enum CheckType {
    SPEED("Speed", "移动速度异常", "speed", EnforcementMode.MITIGATE),
    SPRINT("Sprint", "非法疾跑", "sprint", EnforcementMode.ALERT),
    FLIGHT("Flight", "非法飞行/悬浮", "flight", EnforcementMode.MITIGATE),
    GLIDE("Glide", "异常缓降", "glide", EnforcementMode.MITIGATE),
    ELYTRA("Elytra", "鞘翅飞行异常", "elytra", EnforcementMode.MITIGATE),
    GROUND_SPOOF("GroundSpoof", "落地欺骗", "ground-spoof", EnforcementMode.MITIGATE),
    TIMER("Timer", "移动包速率异常", "timer", EnforcementMode.PUNISH),
    FAST_LADDER("FastLadder", "攀爬速度异常", "fast-ladder", EnforcementMode.MITIGATE),
    STEP("Step", "异常跨越方块", "step", EnforcementMode.MITIGATE),
    PHASE("Phase", "穿墙移动", "phase", EnforcementMode.MITIGATE),
    LIQUID_WALK("LiquidWalk", "液面行走", "liquid-walk", EnforcementMode.ALERT),
    ROTATION("Rotation", "非法视角", "rotation", EnforcementMode.PUNISH),
    BAD_PACKETS("BadPackets", "非法数据包", "bad-packets", EnforcementMode.PUNISH),
    REACH("Reach", "攻击距离超限", "reach", EnforcementMode.PUNISH),
    HITBOX("Hitbox", "攻击射线异常", "hitbox", EnforcementMode.MITIGATE),
    KILL_AURA("KillAura", "攻击视角异常", "kill-aura", EnforcementMode.MITIGATE),
    AUTO_CLICKER("AutoClicker", "点击频率异常", "auto-clicker", EnforcementMode.ALERT),
    NO_SWING("NoSwing", "无挥臂攻击", "no-swing", EnforcementMode.PUNISH),
    CRITICALS("Criticals", "虚假暴击", "criticals", EnforcementMode.MITIGATE),
    VELOCITY("Velocity", "拒绝击退", "velocity", EnforcementMode.PUNISH),
    AUTO_TOTEM("AutoTotem", "自动图腾", "auto-totem", EnforcementMode.ALERT),
    INVENTORY_MOVE("InventoryMove", "开容器移动", "inventory-move", EnforcementMode.ALERT),
    NO_SLOW("NoSlow", "使用物品移速异常", "no-slow", EnforcementMode.MITIGATE),
    FAST_USE("FastUse", "物品使用过快", "fast-use", EnforcementMode.MITIGATE),
    FAST_BOW("FastBow", "弓蓄力异常", "fast-bow", EnforcementMode.MITIGATE),
    CHEST_STEALER("ChestStealer", "容器搬运过快", "chest-stealer", EnforcementMode.MITIGATE),
    FAST_BREAK("FastBreak", "挖掘速度异常", "fast-break", EnforcementMode.MITIGATE),
    SCAFFOLD("Scaffold", "非法搭路", "scaffold", EnforcementMode.MITIGATE),
    ANTI_SPAM("AntiSpam", "聊天刷屏", "anti-spam", EnforcementMode.ALERT),
    ANTI_ADS("AntiAds", "聊天广告", "anti-ads", EnforcementMode.ALERT);

    private final String id;
    private final String display;
    private final String configKey;
    private final EnforcementMode defaultEnforcement;

    CheckType(String id, String display, String configKey, EnforcementMode defaultEnforcement) {
        this.id = id;
        this.display = display;
        this.configKey = configKey;
        this.defaultEnforcement = defaultEnforcement;
    }

    /** 按 {@link #id()} 查找检测项；未知 id 返回 {@code null}。 */
    public static CheckType byId(String id) {
        if (id == null) return null;
        for (CheckType type : values()) {
            if (type.id.equalsIgnoreCase(id)) return type;
        }
        return null;
    }

    public String id() {
        return id;
    }

    /** 展示给玩家/管理员的名称，如 "移动速度异常 (Speed)" */
    public String display() {
        return display + " (" + id + ")";
    }

    public String configKey() {
        return configKey;
    }

    public EnforcementMode defaultEnforcement() {
        return defaultEnforcement;
    }
}
