package com.jay.custompaintings.client.mixin;

import com.jay.custompaintings.library.PaintingRuntime;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.PaintingRenderer;
import net.minecraft.client.renderer.entity.state.PaintingRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla paintings draw an opaque canvas back and edges behind the front face, so even a
 * cutout front texture would show wood backing through transparent PNG holes. Custom slots
 * instead submit only the artwork face with the translucent entity pipeline: opaque pixels
 * remain solid, alpha blends against the wall, and fully transparent holes reveal the wall.
 */
@Mixin(PaintingRenderer.class)
public abstract class PaintingRendererMixin {
    @Shadow
    @Final
    private TextureAtlas paintingsAtlas;

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void custompaintings$renderTransparentArt(PaintingRenderState state, PoseStack poseStack,
                                                      SubmitNodeCollector submitNodeCollector,
                                                      CameraRenderState camera, CallbackInfo ci) {
        PaintingVariant variant = state.variant;
        if (variant == null || PaintingRuntime.slotFromVariant(variant.assetId()).isEmpty()) {
            return;
        }
        TextureAtlasSprite front = this.paintingsAtlas.getSprite(variant.assetId());
        poseStack.pushPose();
        poseStack.rotateDegrees(Axis.YP, 180 - state.direction.get2DDataValue() * 90);
        int width = variant.width();
        int height = variant.height();
        int[] lightCoords = state.lightCoordsPerBlock;
        submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(front.atlasLocation()), (pose, buffer) -> {
            float offsetX = (float) (-width) / 2.0F;
            float offsetY = (float) (-height) / 2.0F;
            double deltaU = 1.0 / width;
            double deltaV = 1.0 / height;
            for (int segmentX = 0; segmentX < width; segmentX++) {
                for (int segmentY = 0; segmentY < height; segmentY++) {
                    float x0 = offsetX + segmentX + 1;
                    float x1 = offsetX + segmentX;
                    float y0 = offsetY + segmentY + 1;
                    float y1 = offsetY + segmentY;
                    int light = lightCoords[segmentX + segmentY * width];
                    float u0 = front.getU((float) (deltaU * (width - segmentX)));
                    float u1 = front.getU((float) (deltaU * (width - segmentX - 1)));
                    float v0 = front.getV((float) (deltaV * (height - segmentY)));
                    float v1 = front.getV((float) (deltaV * (height - segmentY - 1)));
                    vertex(pose, buffer, x0, y1, u1, v0, light);
                    vertex(pose, buffer, x1, y1, u0, v0, light);
                    vertex(pose, buffer, x1, y0, u0, v1, light);
                    vertex(pose, buffer, x0, y0, u1, v1, light);
                }
            }
        });
        poseStack.popPose();
        ci.cancel();
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float u, float v, int light) {
        buffer.addVertex(pose, x, y, -0.03125F)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0, 0, -1);
    }
}
