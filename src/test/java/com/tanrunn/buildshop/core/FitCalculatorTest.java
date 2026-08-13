package com.tanrunn.buildshop.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 背包空间计算测试（设计指南：背包不足时不扣款依赖此逻辑）。 */
class FitCalculatorTest {

    @Test
    void emptyInventoryCapacityIsFreeSlotsTimesMaxStack() {
        int capacity = FitCalculator.capacity(List.of(), 64, 36);
        assertEquals(36 * 64, capacity);
    }

    @Test
    void partiallyFilledSlotsContributeRemainingSpace() {
        List<FitCalculator.Slot> slots = List.of(
                new FitCalculator.Slot(10, 64),
                new FitCalculator.Slot(64, 64)
        );
        int capacity = FitCalculator.capacity(slots, 64, 34);
        assertEquals(54 + 34 * 64, capacity);
    }

    @Test
    void incompatibleSlotsContributeNoSpace() {
        // 不同物品/不同 components 的半满堆叠：不兼容，不得计入可用空间。
        List<FitCalculator.Slot> slots = List.of(
                new FitCalculator.Slot(32, 64, false),
                new FitCalculator.Slot(10, 64, true)
        );
        int capacity = FitCalculator.capacity(slots, 64, 34);
        assertEquals(54 + 34 * 64, capacity);
    }

    @Test
    void incompatibleFullStackIsStillIgnored() {
        List<FitCalculator.Slot> slots = List.of(
                new FitCalculator.Slot(63, 64, false)
        );
        int capacity = FitCalculator.capacity(slots, 64, 1);
        assertEquals(64, capacity);
    }

    @Test
    void compatibleFlagWithDifferentMaxStackUsesSlotMax() {
        // 兼容堆叠按槽位自身最大堆叠计算剩余空间。
        List<FitCalculator.Slot> slots = List.of(
                new FitCalculator.Slot(4, 16, true)
        );
        int capacity = FitCalculator.capacity(slots, 64, 0);
        assertEquals(12, capacity);
    }

    @Test
    void fitsRequiresExactCapacity() {
        assertTrue(FitCalculator.fits(List.of(), 64, 1, 64));
        assertFalse(FitCalculator.fits(List.of(), 64, 1, 65));
        assertTrue(FitCalculator.fits(List.of(), 64, 3, 192));
        assertFalse(FitCalculator.fits(List.of(), 64, 2, 192));
    }

    @Test
    void largeQuantitiesSpanMultipleStacks() {
        assertTrue(FitCalculator.fits(List.of(), 64, 36, 2304));
        assertFalse(FitCalculator.fits(List.of(), 64, 36, 2305));
    }

    @Test
    void capacityNeverOverflowsInt() {
        List<FitCalculator.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            slots.add(new FitCalculator.Slot(1, 64));
        }
        int capacity = FitCalculator.capacity(slots, 64, 1000);
        assertTrue(capacity > 0);
    }
}
