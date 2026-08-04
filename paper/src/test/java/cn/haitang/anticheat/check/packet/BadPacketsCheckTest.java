package cn.haitang.anticheat.check.packet;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BadPacketsCheckTest {

    @Test
    void vanillaReachableCoordinatesAreLegal() {
        assertFalse(BadPacketsCheck.isInvalidCoordinate(0.0));
        assertFalse(BadPacketsCheck.isInvalidCoordinate(100.5));
        assertFalse(BadPacketsCheck.isInvalidCoordinate(-320.0));
        // 原版世界边界 ±29,999,984
        assertFalse(BadPacketsCheck.isInvalidCoordinate(29_999_984.0));
        assertFalse(BadPacketsCheck.isInvalidCoordinate(-29_999_984.0));
    }

    @Test
    void nonFiniteCoordinatesAreInvalid() {
        assertTrue(BadPacketsCheck.isInvalidCoordinate(Double.NaN));
        assertTrue(BadPacketsCheck.isInvalidCoordinate(Double.POSITIVE_INFINITY));
        assertTrue(BadPacketsCheck.isInvalidCoordinate(Double.NEGATIVE_INFINITY));
    }

    @Test
    void coordinatesBeyondWorldBorderMagnitudeAreInvalid() {
        assertTrue(BadPacketsCheck.isInvalidCoordinate(3.1E7));
        assertTrue(BadPacketsCheck.isInvalidCoordinate(-3.1E7));
        // 典型崩服包：极端数量级坐标触发异常区块计算
        assertTrue(BadPacketsCheck.isInvalidCoordinate(1.0E300));
    }

    // ---- 物品数据校验 ----

    @Test
    void plainItemPassesInspection() {
        assertNull(BadPacketsCheck.inspectItem(1, new NBTCompound(), 64, 512, 262144));
        assertNull(BadPacketsCheck.inspectItem(64, null, 64, 512, 262144));
    }

    @Test
    void stackSizeAboveMaximumIsRejected() {
        String violation = BadPacketsCheck.inspectItem(65, null, 64, 512, 262144);
        assertTrue(violation != null && violation.contains("数量"));
    }

    @Test
    void legalStackSizePasses() {
        assertNull(BadPacketsCheck.inspectItem(64, null, 64, 512, 262144));
    }

    @Test
    void deeplyNestedNbtIsRejected() {
        NBTCompound nbt = new NBTCompound();
        NBTCompound cursor = nbt;
        for (int i = 0; i < 600; i++) {
            NBTCompound next = new NBTCompound();
            cursor.setTag("k", next);
            cursor = next;
        }
        String violation = BadPacketsCheck.inspectItem(1, nbt, 64, 512, 262144);
        assertTrue(violation != null && violation.contains("深度"));
    }

    @Test
    void vanillaStyleNbtDepthPasses() {
        NBTCompound nbt = new NBTCompound();
        NBTCompound display = new NBTCompound();
        display.setTag("Name", new NBTString("{\"text\":\"Test\"}"));
        nbt.setTag("display", display);
        nbt.setTag("Unbreakable", new NBTInt(1));
        assertNull(BadPacketsCheck.inspectItem(1, nbt, 64, 512, 262144));
    }

    @Test
    void nbtSizeIsEstimatedFromContents() {
        NBTCompound nbt = new NBTCompound();
        // 成书规模的文本仍应通过 256KB 上限
        NBTList<NBTString> pages = NBTList.createStringList();
        for (int i = 0; i < 100; i++) pages.addTag(new NBTString("x".repeat(1024)));
        nbt.setTag("pages", pages);
        assertTrue(BadPacketsCheck.estimateNbtSize(nbt) > 100_000);
        assertNull(BadPacketsCheck.inspectItem(1, nbt, 64, 512, 262144));

        // 体积爆炸级数据（MB 量级）应被拒绝
        NBTCompound bomb = new NBTCompound();
        NBTList<NBTString> big = NBTList.createStringList();
        for (int i = 0; i < 400_000; i++) big.addTag(new NBTString("pad"));
        bomb.setTag("data", big);
        String violation = BadPacketsCheck.inspectItem(1, bomb, 64, 512, 262144);
        assertTrue(violation != null && violation.contains("体积"));
    }
}
