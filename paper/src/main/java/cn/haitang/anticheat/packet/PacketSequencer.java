package cn.haitang.anticheat.packet;

/**
 * 单个连接的「客户端第几个包」计数器。
 *
 * <p>序号只允许由**客户端入站包**推进。战斗判定按序号差值配对挥臂与攻击
 * （见 {@link PacketTimeline#swingMatches}，窗口仅为 3），因此把服务端下发的包
 * （击退、爆炸冲量等）也计入会插入幽灵序号：近战每次交换都会收到击退，
 * 幽灵序号夹在 SWING 与 ATTACK 之间就会把间隔撑过窗口，让合法玩家被 NoSwing 判违规。
 *
 * <p>服务端来源的采样用 {@link #current()} 标记「落在客户端第几个包之后」，不推进计数。
 */
final class PacketSequencer {

    private long sequence;

    /** 客户端入站包：推进并返回新序号。 */
    long next() {
        return ++sequence;
    }

    /** 服务端出站包：返回当前序号，不推进。 */
    long current() {
        return sequence;
    }
}
