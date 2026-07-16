package cn.haitang.anticheat.check.movement;

/** 穿墙判定的纯几何逻辑。此类不得访问任何 Bukkit 状态，便于单元测试。 */
final class PhaseGeometry {

    private PhaseGeometry() {}

    /** 判定某一空间点是否落在实体方块的碰撞体内部。 */
    @FunctionalInterface
    interface SolidSampler {
        boolean isSolid(double x, double y, double z);
    }

    /**
     * 沿玩家中轴（spine）对 from→to 的直线位移采样，判断玩家是否从空旷处穿入了实体方块。
     *
     * <p>只采样玩家碰撞箱的竖直中轴：贴墙行走时中轴距墙面至少半个箱宽，永远不会落入墙体，
     * 因此不会把"沿墙移动"误判为穿墙；而穿墙时中轴必然扫过墙体内部。</p>
     *
     * <p>起点中轴必须处于空旷处（{@code from} clear）——若起点已在方块内，多为服务端回退/
     * 卡墙的不同步状态，不计入证据，返回 {@code false}。</p>
     *
     * @param spineHeights  相对脚部 Y 的采样高度偏移（依据当前姿态传入，覆盖脚/腰/头）
     * @param segmentSamples from→to 之间的采样段数（含终点），越密越不易漏过薄墙
     */
    static boolean phasedThroughSolid(double fromX, double fromY, double fromZ,
                                      double toX, double toY, double toZ,
                                      double[] spineHeights, int segmentSamples,
                                      SolidSampler sampler) {
        if (spineHeights == null || spineHeights.length == 0) return false;
        // 起点已嵌在方块中：卡墙/回退不同步，不作为穿墙证据
        if (spineSolid(fromX, fromY, fromZ, spineHeights, sampler)) return false;

        int samples = Math.max(1, segmentSamples);
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            double x = fromX + (toX - fromX) * t;
            double y = fromY + (toY - fromY) * t;
            double z = fromZ + (toZ - fromZ) * t;
            if (spineSolid(x, y, z, spineHeights, sampler)) return true;
        }
        return false;
    }

    private static boolean spineSolid(double x, double feetY, double z,
                                      double[] spineHeights, SolidSampler sampler) {
        for (double height : spineHeights) {
            if (sampler.isSolid(x, feetY + height, z)) return true;
        }
        return false;
    }
}
