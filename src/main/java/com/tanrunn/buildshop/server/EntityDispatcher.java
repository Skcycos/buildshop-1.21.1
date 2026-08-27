package com.tanrunn.buildshop.server;

import com.tanrunn.buildshop.BuildShopMod;
import com.tanrunn.buildshop.core.ItemDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 将购买数量适配为在玩家附近生成实体。
 *
 * <p>搜索范围是玩家水平半径 5 格，尝试玩家所在高度附近的 4 个 Y 层。
 * 生成前会检查世界边界和方块碰撞；实际生成发生部分失败时会回滚本次已生成的实体，
 * 让购买引擎可以安全退款。</p>
 */
final class EntityDispatcher implements ItemDispatcher {
    private static final int RADIUS = 5;
    private static final int MAX_POSITIONS = (RADIUS * 2 + 1) * (RADIUS * 2 + 1) * 4;
    private static final int[] Y_OFFSETS = {0, 1, -1, 2};

    private final ServerPlayer player;
    private final EntityType<?> entityType;
    private List<BlockPos> plannedPositions = List.of();

    EntityDispatcher(ServerPlayer player, EntityType<?> entityType) {
        this.player = player;
        this.entityType = entityType;
    }

    @Override
    public boolean canDispense(int quantity) {
        if (quantity <= 0 || quantity > MAX_POSITIONS || !player.isAlive()) {
            plannedPositions = List.of();
            return false;
        }
        plannedPositions = findPositions(quantity);
        return plannedPositions.size() >= quantity;
    }

    @Override
    public boolean dispense(int quantity) {
        if (quantity <= 0) return false;
        List<BlockPos> positions = plannedPositions.size() >= quantity
                ? plannedPositions.subList(0, quantity)
                : findPositions(quantity);
        if (positions.size() < quantity) return false;

        ServerLevel level = player.serverLevel();
        List<Entity> spawned = new ArrayList<>(quantity);
        for (BlockPos position : positions) {
            Entity entity = null;
            try {
                entity = entityType.spawn(level, position, MobSpawnType.COMMAND);
            } catch (RuntimeException exception) {
                BuildShopMod.LOGGER.warn("Failed to spawn shop entity {} for {} at {}",
                        entityType, player.getGameProfile().getName(), position, exception);
            }
            if (entity == null || entity.isRemoved()) {
                if (entity != null && !entity.isRemoved()) entity.discard();
                spawned.forEach(Entity::discard);
                return false;
            }
            spawned.add(entity);
        }
        return true;
    }

    private List<BlockPos> findPositions(int quantity) {
        if (quantity <= 0 || quantity > MAX_POSITIONS) return List.of();

        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        List<BlockPos> result = new ArrayList<>(quantity);
        List<AABB> occupied = new ArrayList<>(quantity);

        // 按水平距离从近到远尝试，保证单只或少量生物尽量出现在玩家身边。
        for (int radius = 1; radius <= RADIUS && result.size() < quantity; radius++) {
            for (int dx = -radius; dx <= radius && result.size() < quantity; dx++) {
                for (int dz = -radius; dz <= radius && result.size() < quantity; dz++) {
                    if (dx * dx + dz * dz > RADIUS * RADIUS
                            || Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy : Y_OFFSETS) {
                        if (result.size() >= quantity
                                || dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) break;
                        BlockPos position = origin.offset(dx, dy, dz);
                        AABB box = entityType.getSpawnAABB(position.getX() + 0.5D,
                                position.getY(), position.getZ() + 0.5D);
                        if (!level.isInWorldBounds(position)
                                || !level.getWorldBorder().isWithinBounds(box)
                                || !level.noCollision(box)) {
                            continue;
                        }
                        boolean overlapsPlanned = false;
                        for (AABB other : occupied) {
                            if (box.intersects(other)) {
                                overlapsPlanned = true;
                                break;
                            }
                        }
                        if (overlapsPlanned) continue;
                        result.add(position);
                        occupied.add(box);
                    }
                }
            }
        }
        return result;
    }
}
