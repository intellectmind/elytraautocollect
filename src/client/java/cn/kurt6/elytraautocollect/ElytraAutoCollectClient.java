package cn.kurt6.elytraautocollect;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ElytraAutoCollectClient implements ClientModInitializer {
    private static KeyMapping toggleKey;
    private long lastTickTime = 0;
    private static final long TICK_INTERVAL = 50;

    private static final KeyMapping.Category CUSTOM_CATEGORY = KeyMapping.Category.register(
            Identifier.parse("category.elytraautocollect")
    );

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.elytraautocollect.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                CUSTOM_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();
            if (now - lastTickTime < TICK_INTERVAL) return;
            lastTickTime = now;
            handleKeyInputs(client);
            try { AutoCollectManager.getInstance().tick(client); }
            catch (Exception e) {
                if (client.player != null)
                    client.player.sendSystemMessage(Component.translatable("msg.elytraautocollect.error", e.getMessage()));
                if (AutoCollectManager.getInstance().isActive()) AutoCollectManager.getInstance().toggle();
            }
        });
    }

    private void handleKeyInputs(net.minecraft.client.Minecraft client) {
        if (toggleKey.consumeClick()) {
            boolean willEnable = !AutoCollectManager.getInstance().isActive();
            if (willEnable && client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
                float y = client.gameRenderer.getMainCamera().yRot();
                AutoCollectManager.getInstance().flightManager.setCruiseYaw(y);
            }
            AutoCollectManager.getInstance().toggle();
        }
    }
}
