package com.jay.custompaintings.client.mixin;

import com.jay.custompaintings.client.CustomPaintingsClient;
import com.jay.custompaintings.client.PaintingRenderStateAccess;
import com.jay.custompaintings.client.PaintingTextureManager;
import com.jay.custompaintings.library.PaintingData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.PaintingRenderer;
import net.minecraft.client.renderer.entity.state.PaintingRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.OptionalInt;

/**
 * Draws custom artwork from a per-painting dynamic texture rather than the painting atlas.
 *
 * <p>Only the artwork face is submitted, using the translucent entity pipeline: opaque pixels
 * stay solid, alpha blends against the wall, and fully transparent holes reveal the wall
 * instead of vanilla's opaque wooden backing. Because the texture is standalone, the number
 * of paintings is not limited by atlas capacity.</p>
 */
@Mixin(PaintingRenderer.class)
public abstract class PaintingRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/decoration/painting/Painting;"
            + "Lnet/minecraft/client/renderer/entity/state/PaintingRenderState;F)V",
            at = @At("TAIL"))
    private void custompaintings$captureId(Painting entity, PaintingRenderState state, float partialTicks,
                                           CallbackInfo ci) {
        OptionalInt id = PaintingData.entityPaintingId(entity);
        ((PaintingRenderStateAccess) state).custompaintings$setPaintingId(id.isPresent() ? id.getAsInt() : -1);
    }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void custompaintings$renderArtwork(PaintingRenderState state, PoseStack poseStack,
                                               SubmitNodeCollector submitNodeCollector,
                                               CameraRenderState camera, CallbackInfo ci) {
        int paintingId = ((PaintingRenderStateAccess) state).custompaintings$getPaintingId();
        PaintingVariant variant = state.variant;
        if (paintingId < 0 || variant == null) {
            return;
        }
        PaintingTextureManager manager = CustomPaintingsClient.paintings();
        if (manager == null) {
            return;
        }
        PaintingTextureManager.Texture texture = manager.artwork(paintingId);
        if (texture == null) {
            // Artwork is still streaming in; draw nothing rather than a checkerboard.
            ci.cancel();
            return;
        }
        int width = variant.width();
        int height = variant.height();
        int[] lightCoords = state.lightCoordsPerBlock;
        poseStack.pushPose();
        poseStack.rotateDegrees(Axis.YP, 180 - state.direction.get2DDataValue() * 90);
        submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(texture.id()),
                (pose, buffer) -> {
                    float offsetX = (float) (-width) / 2.0F;
                    float offsetY = (float) (-height) / 2.0F;
                    for (int segmentX = 0; segmentX < width; segmentX++) {
                        for (int segmentY = 0; segmentY < height; segmentY++) {
                            float x0 = offsetX + segmentX + 1;
                            float x1 = offsetX + segmentX;
                            float y0 = offsetY + segmentY + 1;
                            float y1 = offsetY + segmentY;
                            int light = lightCoords.length > segmentX + segmentY * width
                                    ? lightCoords[segmentX + segmentY * width] : 0xF000F0;
                            // Standalone texture: UVs span the whole image, not an atlas cell.
                            float u0 = (float) (width - segmentX - 1) / width;
                            float u1 = (float) (width - segmentX) / width;
                            float v0 = (float) (height - segmentY - 1) / height;
                            float v1 = (float) (height - segmentY) / height;
                            vertex(pose, buffer, x0, y1, u0, v1, light);
                            vertex(pose, buffer, x1, y1, u1, v1, light);
                            vertex(pose, buffer, x1, y0, u1, v0, light);
                            vertex(pose, buffer, x0, y0, u0, v0, light);
                        }
                    }
                });
        poseStack.popPose();
        ci.cancel();
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y,
                               float u, float v, int light) {
        buffer.addVertex(pose, x, y, -0.03125F)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0, 0, -1);
    }
}
