package com.jay.custompaintings.client;

import com.jay.custompaintings.item.CustomPaintingItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * Item icon for a custom painting, drawn from its own dynamic texture.
 *
 * <p>Minecraft's special-model-renderer registry is private and bootstrap-only, so instead of
 * registering a codec this class implements {@link ItemModel} directly and is returned by
 * {@code ModelManagerMixin} for our item id. One model therefore serves unlimited paintings,
 * with the icon selected by the stack's painting-id component.</p>
 */
public final class PaintingItemModel implements ItemModel {
    private static final Vector3fc[] EXTENTS = new Vector3fc[]{
            new Vector3f(-0.5F, -0.5F, 0.0F), new Vector3f(0.5F, -0.5F, 0.0F),
            new Vector3f(0.5F, 0.5F, 0.0F), new Vector3f(-0.5F, 0.5F, 0.0F)};

    @Override
    public void update(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver,
                       ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
        int paintingId = CustomPaintingItems.paintingId(stack);
        output.appendModelIdentityElement(this);
        output.appendModelIdentityElement(paintingId);
        ItemStackRenderState.LayerRenderState layer = output.newLayer();
        layer.setExtents(() -> EXTENTS);
        layer.setUsesBlockLight(false);
        if (stack.hasFoil()) {
            layer.setFoilType(ItemStackRenderState.FoilType.STANDARD);
            output.setAnimated();
        }
        layer.setupSpecialModel(new Renderer(), paintingId);
    }

    /** Draws the painting preview as a flat, correctly-lit quad in item space. */
    private static final class Renderer
            implements net.minecraft.client.renderer.special.SpecialModelRenderer<Integer> {
        @Override
        public Integer extractArgument(ItemStack stack) {
            return CustomPaintingItems.paintingId(stack);
        }

        @Override
        public void getExtents(Consumer<Vector3fc> output) {
            for (Vector3fc extent : EXTENTS) {
                output.accept(extent);
            }
        }

        @Override
        public void submit(Integer paintingId, PoseStack poseStack, SubmitNodeCollector collector,
                           int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
            if (paintingId == null || paintingId < 0) {
                return;
            }
            PaintingTextureManager manager = CustomPaintingsClient.paintings();
            if (manager == null) {
                return;
            }
            PaintingTextureManager.Texture texture = manager.preview(paintingId);
            if (texture == null) {
                return;
            }
            var entry = CustomPaintingsClient.library().entry(paintingId).orElse(null);
            // Preserve the painting's aspect ratio inside the icon square.
            float aspect = entry == null ? 1.0F : (float) entry.width() / entry.height();
            float halfWidth = aspect >= 1.0F ? 0.5F : 0.5F * aspect;
            float halfHeight = aspect >= 1.0F ? 0.5F / aspect : 0.5F;
            int color = outlineColor != 0 ? outlineColor : -1;
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(texture.id()), (pose, buffer) -> {
                // Item space is centred on the origin; GUI applies a Y flip, so V is inverted.
                quad(pose, buffer, -halfWidth, -halfHeight, halfWidth, halfHeight,
                        lightCoords, overlayCoords, color);
            });
        }

        private static void quad(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0,
                                 float x1, float y1, int light, int overlay, int color) {
            vertex(pose, buffer, x0, y0, 0.0F, 1.0F, light, overlay, color);
            vertex(pose, buffer, x1, y0, 1.0F, 1.0F, light, overlay, color);
            vertex(pose, buffer, x1, y1, 1.0F, 0.0F, light, overlay, color);
            vertex(pose, buffer, x0, y1, 0.0F, 0.0F, light, overlay, color);
        }

        private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y,
                                   float u, float v, int light, int overlay, int color) {
            buffer.addVertex(pose, x, y, 0.0F)
                    .setColor(color)
                    .setUv(u, v)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, 0, 0, 1);
        }
    }
}
