package com.example.esp;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

/**
 * Storage ESP + Item ESP (client-side).
 * Keys: K = toggle storage ESP, J = toggle item ESP (change in Controls menu).
 */
public class EspMod implements ClientModInitializer {

    private static boolean storageEsp = true;
    private static boolean itemEsp = true;

    private static final int CHUNK_RADIUS = 6;      // chunks around the player
    private static final double ITEM_RANGE = 64.0;  // blocks

    private static KeyBinding toggleStorageKey;
    private static KeyBinding toggleItemKey;

    @Override
    public void onInitializeClient() {
        toggleStorageKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.storageitemesp.storage", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.storageitemesp"));
        toggleItemKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.storageitemesp.items", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.storageitemesp"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleStorageKey.wasPressed()) {
                storageEsp = !storageEsp;
                message(client, "Storage ESP: " + (storageEsp ? "ON" : "OFF"));
            }
            while (toggleItemKey.wasPressed()) {
                itemEsp = !itemEsp;
                message(client, "Item ESP: " + (itemEsp ? "ON" : "OFF"));
            }
        });

        WorldRenderEvents.LAST.register(EspMod::render);
    }

    private static void message(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), true);
        }
    }

    private static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null || mc.player == null || (!storageEsp && !itemEsp)) {
            return;
        }

        MatrixStack matrices = ctx.matrixStack();
        Vec3d cam = ctx.camera().getPos();
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        if (storageEsp) {
            int pcx = mc.player.getChunkPos().x;
            int pcz = mc.player.getChunkPos().z;
            for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
                for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                    WorldChunk chunk = world.getChunkManager().getWorldChunk(pcx + dx, pcz + dz);
                    if (chunk == null) continue;
                    for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                        BlockEntity be = e.getValue();
                        float[] color = storageColor(be);
                        if (color == null) continue;
                        BlockPos pos = e.getKey();
                        Box box = world.getBlockState(pos).getOutlineShape(world, pos).isEmpty()
                                ? new Box(pos)
                                : world.getBlockState(pos).getOutlineShape(world, pos).getBoundingBox().offset(pos);
                        drawBox(buffer, matrix, box, cam, color[0], color[1], color[2], 1.0f);
                    }
                }
            }
        }

        if (itemEsp) {
            double rangeSq = ITEM_RANGE * ITEM_RANGE;
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ItemEntity)) continue;
                if (entity.squaredDistanceTo(mc.player) > rangeSq) continue;
                Box box = entity.getBoundingBox().expand(0.05);
                drawBox(buffer, matrix, box, cam, 0.0f, 0.9f, 1.0f, 1.0f);
            }
        }

        tessellator.draw();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
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

    private static void drawBox(BufferBuilder b, Matrix4f m, Box box, Vec3d cam,
                                float r, float g, float bl, float a) {
        float x1 = (float) (box.minX - cam.x), y1 = (float) (box.minY - cam.y), z1 = (float) (box.minZ - cam.z);
        float x2 = (float) (box.maxX - cam.x), y2 = (float) (box.maxY - cam.y), z2 = (float) (box.maxZ - cam.z);

        // bottom face
        line(b, m, x1, y1, z1, x2, y1, z1, r, g, bl, a);
        line(b, m, x2, y1, z1, x2, y1, z2, r, g, bl, a);
        line(b, m, x2, y1, z2, x1, y1, z2, r, g, bl, a);
        line(b, m, x1, y1, z2, x1, y1, z1, r, g, bl, a);
        // top face
        line(b, m, x1, y2, z1, x2, y2, z1, r, g, bl, a);
        line(b, m, x2, y2, z1, x2, y2, z2, r, g, bl, a);
        line(b, m, x2, y2, z2, x1, y2, z2, r, g, bl, a);
        line(b, m, x1, y2, z2, x1, y2, z1, r, g, bl, a);
        // vertical edges
        line(b, m, x1, y1, z1, x1, y2, z1, r, g, bl, a);
        line(b, m, x2, y1, z1, x2, y2, z1, r, g, bl, a);
        line(b, m, x2, y1, z2, x2, y2, z2, r, g, bl, a);
        line(b, m, x1, y1, z2, x1, y2, z2, r, g, bl, a);
    }

    private static void line(BufferBuilder b, Matrix4f m,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float bl, float a) {
        b.vertex(m, x1, y1, z1).color(r, g, bl, a).next();
        b.vertex(m, x2, y2, z2).color(r, g, bl, a).next();
    }
}
