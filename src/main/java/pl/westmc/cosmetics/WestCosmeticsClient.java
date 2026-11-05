package pl.westmc.cosmetics;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.render.entity.PlayerEntityRenderer;

public final class WestCosmeticsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CosmeticsData.start();
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
            if (entityRenderer instanceof PlayerEntityRenderer playerRenderer) {
                registrationHelper.register(new CosmeticsFeature(playerRenderer));
            }
        });
    }
}
