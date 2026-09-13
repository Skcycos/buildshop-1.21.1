package com.tanrunn.buildshop.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildShopNetworkTest {

    @Test
    void openPayloadCarriesTheServerSelectedUiBackend() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        BuildShopNetwork.OpenShopPayload.STREAM_CODEC.encode(
                buffer, new BuildShopNetwork.OpenShopPayload("ldlib2"));

        assertEquals("ldlib2", BuildShopNetwork.OpenShopPayload.STREAM_CODEC.decode(buffer).uiBackend());
    }
}
