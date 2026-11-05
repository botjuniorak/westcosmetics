package pl.westmc.cosmetics;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.Set;

/** Rysuje kosmetyki WestMC na graczu. */
public final class CosmeticsFeature extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    private static final int FULL_BRIGHT = 0xF000F0;

    private final ModelPart wings;
    private final ModelPart wingLeft, wingRight;
    private final ModelPart topHat, crown, halo;

    public CosmeticsFeature(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);

        // --- skrzydla: dwa skrzydla z dwoch czesci (ramie + piora) ---
        ModelData wd = new ModelData();
        ModelPartData wr = wd.getRoot();
        ModelPartData left = wr.addChild("left", ModelPartBuilder.create()
                .uv(0, 0).cuboid(0.0F, -3.0F, 0.0F, 18.0F, 22.0F, 1.0F, Dilation.NONE),
            ModelTransform.pivot(1.5F, 1.0F, 2.6F));
        ModelPartData right = wr.addChild("right", ModelPartBuilder.create()
                .uv(0, 0).mirrored().cuboid(-18.0F, -3.0F, 0.0F, 18.0F, 22.0F, 1.0F, Dilation.NONE),
            ModelTransform.pivot(-1.5F, 1.0F, 2.6F));
        wings = TexturedModelData.of(wd, 64, 32).createModel();
        wingLeft = wings.getChild("left");
        wingRight = wings.getChild("right");

        // --- cylinder ---
        ModelData hd = new ModelData();
        hd.getRoot().addChild("brim", ModelPartBuilder.create()
                .uv(0, 0).cuboid(-5.5F, -9.0F, -5.5F, 11.0F, 1.0F, 11.0F, Dilation.NONE), ModelTransform.NONE);
        hd.getRoot().addChild("crown", ModelPartBuilder.create()
                .uv(0, 12).cuboid(-4.0F, -17.0F, -4.0F, 8.0F, 8.0F, 8.0F, Dilation.NONE), ModelTransform.NONE);
        hd.getRoot().addChild("band", ModelPartBuilder.create()
                .uv(32, 12).cuboid(-4.0F, -10.5F, -4.0F, 8.0F, 1.5F, 8.0F, new Dilation(0.15F)), ModelTransform.NONE);
        topHat = TexturedModelData.of(hd, 64, 32).createModel();

        // --- korona: 4 sciany + zabki ---
        ModelData cd = new ModelData();
        ModelPartData c = cd.getRoot();
        c.addChild("front", ModelPartBuilder.create().uv(0, 0).cuboid(-4.5F, -11.0F, -4.5F, 9.0F, 3.0F, 1.0F, Dilation.NONE), ModelTransform.NONE);
        c.addChild("back", ModelPartBuilder.create().uv(0, 0).cuboid(-4.5F, -11.0F, 3.5F, 9.0F, 3.0F, 1.0F, Dilation.NONE), ModelTransform.NONE);
        c.addChild("left", ModelPartBuilder.create().uv(0, 4).cuboid(3.5F, -11.0F, -3.5F, 1.0F, 3.0F, 7.0F, Dilation.NONE), ModelTransform.NONE);
        c.addChild("right", ModelPartBuilder.create().uv(0, 4).cuboid(-4.5F, -11.0F, -3.5F, 1.0F, 3.0F, 7.0F, Dilation.NONE), ModelTransform.NONE);
        int i = 0;
        for (float x : new float[]{-4.5F, -0.5F, 3.5F})
            for (float z : new float[]{-4.5F, 3.5F})
                c.addChild("spike" + (i++), ModelPartBuilder.create().uv(20, 0).cuboid(x, -13.0F, z, 1.0F, 2.0F, 1.0F, Dilation.NONE), ModelTransform.NONE);
        for (float z : new float[]{-0.5F})
            for (float x : new float[]{-4.5F, 3.5F})
                c.addChild("spike" + (i++), ModelPartBuilder.create().uv(20, 0).cuboid(x, -13.0F, z, 1.0F, 2.0F, 1.0F, Dilation.NONE), ModelTransform.NONE);
        c.addChild("gem", ModelPartBuilder.create().uv(24, 0).cuboid(-1.0F, -10.5F, -5.0F, 2.0F, 2.0F, 1.0F, Dilation.NONE), ModelTransform.NONE);
        crown = TexturedModelData.of(cd, 32, 16).createModel();

        // --- aureola: kwadratowy pierscien nad glowa ---
        ModelData ad = new ModelData();
        ModelPartData a = ad.getRoot().addChild("ring", ModelPartBuilder.create()
                .uv(0, 0).cuboid(-4.0F, 0.0F, -5.0F, 8.0F, 1.0F, 1.0F, Dilation.NONE)
                .uv(0, 0).cuboid(-4.0F, 0.0F, 4.0F, 8.0F, 1.0F, 1.0F, Dilation.NONE)
                .uv(0, 2).cuboid(-5.0F, 0.0F, -4.0F, 1.0F, 1.0F, 8.0F, Dilation.NONE)
                .uv(0, 2).cuboid(4.0F, 0.0F, -4.0F, 1.0F, 1.0F, 8.0F, Dilation.NONE),
            ModelTransform.pivot(0.0F, -12.5F, 0.0F));
        halo = TexturedModelData.of(ad, 32, 16).createModel();
    }

    private static Identifier tex(Cosmetic c) {
        return Identifier.of("westcosmetics", "textures/cosmetic/" + c.texture + ".png");
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        if (state.invisible) return;
        Set<String> owned = CosmeticsData.get(state.name);
        if (owned.isEmpty()) return;

        Cosmetic wingsC = null, headC = null, haloC = null;
        for (String id : owned) {
            Cosmetic c = Cosmetic.byId(id);
            if (c == null) continue;
            switch (c.slot) {
                case SKRZYDLA -> wingsC = c;
                case GLOWA -> headC = c;
                case AUREOLA -> haloC = c;
            }
        }
        float age = state.age;

        if (wingsC != null && !state.isGliding) {
            matrices.push();
            this.getContextModel().body.rotate(matrices);
            float flap = MathHelper.sin(age * 0.12F) * 0.18F;
            float open = state.isInSneakingPose ? 0.15F : 0.45F;
            wingLeft.yaw = open + flap;
            wingRight.yaw = -(open + flap);
            wingLeft.roll = -0.12F;
            wingRight.roll = 0.12F;
            wingLeft.pitch = wingRight.pitch = 0.18F;
            wings.render(matrices, vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(tex(wingsC))),
                light, OverlayTexture.DEFAULT_UV);
            matrices.pop();
        }

        if (headC != null) {
            matrices.push();
            this.getContextModel().head.rotate(matrices);
            ModelPart m = headC == Cosmetic.KORONA ? crown : topHat;
            m.render(matrices, vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(tex(headC))),
                light, OverlayTexture.DEFAULT_UV);
            matrices.pop();
        }

        if (haloC != null) {
            matrices.push();
            this.getContextModel().head.rotate(matrices);
            ModelPart ring = halo.getChild("ring");
            ring.yaw = age * 0.04F;
            ring.pivotY = -12.5F + MathHelper.sin(age * 0.08F) * 0.4F;
            halo.render(matrices, vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(tex(haloC))),
                FULL_BRIGHT, OverlayTexture.DEFAULT_UV);
            matrices.pop();
        }
    }
}
