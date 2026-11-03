package com.example.esp;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Storage ESP + Item ESP (client-side), Minecraft 1.21.11.
 * Keys: K = toggle storage ESP, J = toggle item ESP (changeable in Controls).
 */
public class EspMod implements ClientModInitializer {

    public static final String MOD_ID = "storageitemesp";

    private static boolean storageEsp = true;
    private static boolean itemEsp = true;

    private static final int CHUNK_RADIUS = 6;      // chunks around the player
    private static final double ITEM_RANGE = 64.0;  // blocks
    private static final float FILL_ALPHA = 0.35f;

    private static KeyMapping toggleStorageKey;
    private static KeyMapping toggleItemKey;

    // Draw-through-walls pipeline (vanilla filled-debug pipeline with depth test disabled)
    private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/esp_filled_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());

    private static final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private BufferBuilder buffer;
    private MappableRingBuffer vertexBuffer;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleStorageKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.storageitemesp.storage", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        toggleItemKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.storageitemesp.items", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleStorageKey.consumeClick()) {
                storageEsp = !storageEsp;
                message(client, "Storage ESP: " + (storageEsp ? "ON" : "OFF"));
            }
            while (toggleItemKey.consumeClick()) {
                itemEsp = !itemEsp;
                message(client, "Item ESP: " + (itemEsp ? "ON" : "OFF"));
            }
        });

        WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::onRender);
    }

    private static void message(Minecraft client, String text) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal(text), true);
        }
    }

    private void onRender(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || (!storageEsp && !itemEsp)) {
            return;
        }

        PoseStack matrices = context.matrices();
        Vec3 camera = context.worldState().cameraRenderState.pos;

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc pose = matrices.last().pose();

        if (storageEsp) {
            int pcx = mc.player.getBlockX() >> 4;
            int pcz = mc.player.getBlockZ() >> 4;
            for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
                for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                    ChunkAccess access = level.getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                    if (!(access instanceof LevelChunk chunk)) continue;
                    for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                        float[] color = storageColor(e.getValue());
                        if (color == null) continue;
                        BlockPos pos = e.getKey();
                        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
                        AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
                        addBox(pose, box, color[0], color[1], color[2]);
                    }
                }
            }
        }

        if (itemEsp) {
            double rangeSq = ITEM_RANGE * ITEM_RANGE;
            for (Entity entity : level.entitiesForRendering()) {
                if (!(entity instanceof ItemEntity)) continue;
                if (entity.distanceToSqr(mc.player) > rangeSq) continue;
                addBox(pose, entity.getBoundingBox().inflate(0.05), 0.0f, 0.9f, 1.0f);
            }
        }

        matrices.popPose();

        if (buffer != null) {
            drawBuffered(mc);
        }
    }

    /** Returns RGB for supported storage blocks, or null if the block entity is not storage. */
    private static float[] storageColor(BlockEntity be) {
        if (be instanceof EnderChestBlockEntity) return new float[]{0.6f, 0.2f, 1.0f};
        if (be instanceof ChestBlockEntity)      return new float[]{1.0f, 0.6f, 0.0f};
        if (be instanceof BarrelBlockEntity)     return new float[]{0.7f, 0.45f, 0.2f};
        if (be instanceof ShulkerBoxBlockEntity) return new float[]{1.0f, 0.4f, 0.8f};
        if (be instanceof HopperBlockEntity)     return new float[]{0.6f, 0.6f, 0.6f};
        if (be instanceof DispenserBlockEntity)  return new float[]{0.9f, 0.9f, 0.3f};
        if (be instanceof AbstractFurnaceBlockEntity) return new float[]{0.9f, 0.3f, 0.3f};
        return null;
    }

    private void addBox(Matrix4fc pose, AABB b, float r, float g, float bl) {
        if (buffer == null) {
            buffer = new BufferBuilder(allocator, FILLED_THROUGH_WALLS.getVertexFormatMode(), FILLED_THROUGH_WALLS.getVertexFormat());
        }
        renderFilledBox(pose, buffer,
                (float) b.minX, (float) b.minY, (float) b.minZ,
                (float) b.maxX, (float) b.maxY, (float) b.maxZ,
                r, g, bl, FILL_ALPHA);
    }

    private void drawBuffered(Minecraft client) {
        MeshData built = buffer.buildOrThrow();
        MeshData.DrawState drawState = built.drawState();
        VertexFormat format = drawState.format();

        GpuBuffer vertices = upload(drawState, format, built);
        draw(client, FILLED_THROUGH_WALLS, built, drawState, vertices, format);

        vertexBuffer.rotate();
        buffer = null;
    }

    private GpuBuffer upload(MeshData.DrawState drawState, VertexFormat format, MeshData built) {
        int vertexBufferSize = drawState.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            vertexBuffer = new MappableRingBuffer(() -> MOD_ID + " esp vertex buffer",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, built.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(built.vertexBuffer(), mappedView.data());
        }

        return vertexBuffer.currentBuffer();
    }

    private static void draw(Minecraft client, RenderPipeline pipeline, MeshData built,
                             MeshData.DrawState drawState, GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            built.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(built.indexBuffer());
            indexType = built.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawState.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> MOD_ID + " esp rendering",
                        client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                        client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);

            renderPass.drawIndexed(0, 0, drawState.indexCount(), 1);
        }

        built.close();
    }

    private static void renderFilledBox(Matrix4fc m, BufferBuilder b,
                                        float minX, float minY, float minZ,
                                        float maxX, float maxY, float maxZ,
                                        float r, float g, float bl, float a) {
        // Front (z+)
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        // Back (z-)
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        // Left (x-)
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        // Right (x+)
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        // Top
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        // Bottom
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
    }
}
