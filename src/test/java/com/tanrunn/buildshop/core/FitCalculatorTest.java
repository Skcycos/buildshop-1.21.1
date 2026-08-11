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
