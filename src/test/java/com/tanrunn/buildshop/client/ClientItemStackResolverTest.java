package com.tanrunn.buildshop.client;

import com.tanrunn.buildshop.core.StockMode;
import com.tanrunn.buildshop.network.BuildShopNetwork.ProductDto;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientItemStackResolverTest {
    @Test
    void parsesCanonicalItemStackSnbtWithoutAui() {
        ItemStack stack = ClientItemStackResolver.parse("{id:\"minecraft:stone\",count:1}");

        assertEquals(Items.STONE, stack.getItem());
        assertEquals(1, stack.getCount());
    }

    @Test
    void entityProductsNeverPretendToHaveAnItemIcon() {
        ProductDto entity = new ProductDto("cow", "", "minecraft:cow", null, "Cow", "", "virtual_coins",
                10, "10", 1, 64, StockMode.INFINITE, -1, true, List.of(), 0);

        assertTrue(ClientItemStackResolver.resolve(entity).isEmpty());
    }
}
