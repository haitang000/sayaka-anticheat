package cn.haitang.anticheat.check;

/**
 * 所有检测项。configKey 对应 config.yml 中 checks.<key> 配置段。
 */
public enum CheckType {
    SPEED("Speed", "移动速度异常", "speed"),
    FLIGHT("Flight", "非法飞行/悬浮", "flight"),
    GROUND_SPOOF("GroundSpoof", "落地欺骗", "ground-spoof"),
    TIMER("Timer", "移动包速率异常", "timer"),
    FAST_LADDER("FastLadder", "攀爬速度异常", "fast-ladder"),
    STEP("Step", "异常跨越方块", "step"),
    ROTATION("Rotation", "非法视角", "rotation"),
    BAD_PACKETS("BadPackets", "非法数据包", "bad-packets"),
    REACH("Reach", "攻击距离超限", "reach"),
    KILL_AURA("KillAura", "攻击视角异常", "kill-aura"),
    AUTO_CLICKER("AutoClicker", "点击频率异常", "auto-clicker"),
    NO_SWING("NoSwing", "无挥臂攻击", "no-swing"),
    CRITICALS("Criticals", "虚假暴击", "criticals"),
    VELOCITY("Velocity", "拒绝击退", "velocity"),
    AUTO_TOTEM("AutoTotem", "自动图腾", "auto-totem"),
    INVENTORY_MOVE("InventoryMove", "开容器移动", "inventory-move"),
    NO_SLOW("NoSlow", "使用物品移速异常", "no-slow"),
    FAST_USE("FastUse", "物品使用过快", "fast-use"),
    FAST_BOW("FastBow", "弓蓄力异常", "fast-bow"),
    CHEST_STEALER("ChestStealer", "容器搬运过快", "chest-stealer"),
    FAST_BREAK("FastBreak", "挖掘速度异常", "fast-break"),
    SCAFFOLD("Scaffold", "非法搭路", "scaffold"),
    ANTI_SPAM("AntiSpam", "聊天刷屏", "anti-spam"),
    ANTI_ADS("AntiAds", "聊天广告", "anti-ads");

    private final String id;
    private final String display;
    private final String configKey;

    CheckType(String id, String display, String configKey) {
        this.id = id;
        this.display = display;
        this.configKey = configKey;
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
}
