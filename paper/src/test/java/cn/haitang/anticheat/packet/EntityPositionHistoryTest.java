package cn.haitang.anticheat.packet;

import cn.haitang.anticheat.packet.EntityPositionHistory.Snapshot;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EntityPositionHistoryTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();

    private static Snapshot at(int tick, double x) {
        return new Snapshot(tick, WORLD, new BoundingBox(x, 0, 0, x + 0.6, 1.8, 0.6));
    }

    @Test
    void unionCoversEveryTickInsideTheInterpolationWindow() {
        // 目标每 tick 沿 X 前进 0.3：并集必须同时覆盖客户端可能渲染的新旧位置
        List<Snapshot> history = List.of(at(97, 0.0), at(98, 0.3), at(99, 0.6), at(100, 0.9));

        BoundingBox box = EntityPositionHistory.unionWindow(history, WORLD, 100, 3);

        assertEquals(0.0, box.getMinX(), 1.0e-9);
        assertEquals(1.5, box.getMaxX(), 1.0e-9);
    }

    @Test
    void windowZeroKeepsTheSingleTickSnapshot() {
        List<Snapshot> history = List.of(at(99, 0.3), at(100, 0.9));

        BoundingBox box = EntityPositionHistory.unionWindow(history, WORLD, 100, 0);

        assertEquals(0.9, box.getMinX(), 1.0e-9);
        assertEquals(1.5, box.getMaxX(), 1.0e-9);
    }

    @Test
    void snapshotsNewerThanTheWantedTickAreIgnored() {
        List<Snapshot> history = List.of(at(99, 0.3), at(100, 0.6), at(101, 5.0));

        BoundingBox box = EntityPositionHistory.unionWindow(history, WORLD, 100, 3);

        assertEquals(1.2, box.getMaxX(), 1.0e-9);
    }

    @Test
    void fallsBackToTheNewestOlderSnapshotWhenTheWindowIsEmpty() {
        // 历史存在但都早于窗口（如目标短暂未被采样），退回最近的旧快照
        List<Snapshot> history = List.of(at(90, 0.0), at(92, 0.3));

        BoundingBox box = EntityPositionHistory.unionWindow(history, WORLD, 100, 3);

        assertEquals(0.3, box.getMinX(), 1.0e-9);
        assertEquals(0.9, box.getMaxX(), 1.0e-9);
    }

    @Test
    void otherWorldSnapshotsNeverLeakIntoTheUnion() {
        List<Snapshot> history = List.of(
                new Snapshot(99, OTHER_WORLD, new BoundingBox(50, 0, 0, 50.6, 1.8, 0.6)),
                at(100, 0.6));

        BoundingBox box = EntityPositionHistory.unionWindow(history, WORLD, 100, 3);

        assertEquals(0.6, box.getMinX(), 1.0e-9);
        assertEquals(1.2, box.getMaxX(), 1.0e-9);
    }

    @Test
    void returnsNullWhenNothingMatches() {
        assertNull(EntityPositionHistory.unionWindow(List.of(at(101, 0.0)), WORLD, 100, 3));
        assertNull(EntityPositionHistory.unionWindow(
                List.of(new Snapshot(100, OTHER_WORLD, new BoundingBox(0, 0, 0, 1, 1, 1))),
                WORLD, 100, 3));
    }

    @Test
    void unionDoesNotMutateTheStoredSnapshots() {
        Snapshot first = at(99, 0.0);
        Snapshot second = at(100, 0.9);
        BoundingBox box = EntityPositionHistory.unionWindow(
                List.of(first, second), WORLD, 100, 3);
        box.expand(10.0);

        assertEquals(0.0, first.box().getMinX(), 1.0e-9);
        assertEquals(0.6, first.box().getMaxX(), 1.0e-9);
        assertEquals(0.9, second.box().getMinX(), 1.0e-9);
        assertEquals(1.5, second.box().getMaxX(), 1.0e-9);
    }
}
