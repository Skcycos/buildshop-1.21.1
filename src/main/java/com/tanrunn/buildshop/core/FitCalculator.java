package com.tanrunn.buildshop.core;

/**
 * 背包空间计算（纯逻辑）。
 *
 * <p>输入为背包内每个已占槽位的 {@code (count, maxStack)}，以及要放入的物品最大堆叠，
 * 计算还能放下多少个物品。可放入多个堆叠。</p>
 *
 * <p>兼容性：只有 {@link Slot#compatible()} 为 true 的槽位才能与待放物品合并；
 * 不同物品、不同 Data Components 的已有堆叠不占用可用空间（由调用方判定）。</p>
 */
public final class FitCalculator {
    private FitCalculator() {
    }

    /** 单个槽位快照。 */
    public record Slot(int count, int maxStack, boolean compatible) {

        public Slot(int count, int maxStack) {
            this(count, maxStack, true);
        }
    }

    /**
     * 计算还能容纳多少个 {@code itemMaxStack} 物品。
     *
     * @param slots        已占槽位列表（不兼容的槽位不参与计算）
     * @param itemMaxStack 目标物品最大堆叠
     * @param freeSlots    空槽位数量
     */
    public static int capacity(Iterable<Slot> slots, int itemMaxStack, int freeSlots) {
        long capacity = 0;
        if (itemMaxStack <= 0) return 0;
        for (Slot slot : slots) {
            if (slot.compatible && slot.count < slot.maxStack) {
                capacity += slot.maxStack - slot.count;
            }
        }
        capacity += (long) freeSlots * itemMaxStack;
        if (capacity > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) capacity;
    }

    /**
     * 能否容纳恰好 {@code quantity} 个物品（整笔购买要求）。
     */
    public static boolean fits(Iterable<Slot> slots, int itemMaxStack, int freeSlots, int quantity) {
        return capacity(slots, itemMaxStack, freeSlots) >= quantity;
    }
}
