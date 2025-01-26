package com.pathtracker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.util.math.Direction;

import com.mojang.blaze3d.systems.RenderSystem;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;

@Environment(EnvType.CLIENT)
public class PathTrackerClientMod implements ClientModInitializer {

    // Whether we’re currently tracking player movement
    private static boolean trackingEnabled = false;
    // Whether we’re rendering the block overlays
    private static boolean renderingEnabled = false;

    // Overlay color components (defaults to red)
    private static float cubeRed = 1.0f;
    private static float cubeGreen = 0.0f;
    private static float cubeBlue = 0.0f;

    // Disable/enable depth override
    private static boolean depthOverride = false;

    // Key bindings (press to toggle)
    private static KeyBinding toggleTrackingKey;
    private static KeyBinding toggleRenderingKey;

    // Map to hold visited positions per dimension
    private Map<RegistryKey<World>, Set<BlockPos>> visitedPositionsMap = new HashMap<>();
    // Storage for path data (for everything)
    PathStorageSessions pathStorageSessions = new PathStorageSessions("pathtracer");
    private BlockPos lastTrackedPos = null;
    private String currentMap = null;

    @Override
    public void onInitializeClient() {
        System.out.println("[PathTracker] onInitializeClient called!");

        // Load from DB...
        setCubeColorFromHex(pathStorageSessions.getColor());

        // ------------------------------------------------
        // 1) Setup key bindings
        // ------------------------------------------------
        toggleTrackingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Path Tracker Toggle Tracking",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "PathTracker"
        ));
        toggleRenderingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Path Tracker Toggle Rendering",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                "PathTracker"
        ));

        // ------------------------------------------------
        // 2) Event registrations
        // ------------------------------------------------
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(this::onWorldRender);

        // Save path data when shutting down
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveAllPathData());

        // ------------------------------------------------
        // 3) Commands
        // ------------------------------------------------
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                literal("pathtracker")
                    // /pathtracker track on|off
                    .then(literal("track")
                        .then(literal("on").executes(ctx -> {
                            trackingEnabled = true;
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Path tracking enabled."));
                            return 1;
                        }))
                        .then(literal("off").executes(ctx -> {
                            trackingEnabled = false;
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Path tracking disabled."));
                            return 1;
                        }))
                    )
                    // /pathtracker render on|off
                    .then(literal("render")
                        .then(literal("on").executes(ctx -> {
                            renderingEnabled = true;
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Path rendering enabled."));
                            return 1;
                        }))
                        .then(literal("off").executes(ctx -> {
                            renderingEnabled = false;
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Path rendering disabled."));
                            return 1;
                        }))
                    )
                    // /pathtracker color <hex>
                    .then(literal("color")
                        .then(argument("hex", StringArgumentType.word())
                            .executes(ctx -> {
                                String hex = StringArgumentType.getString(ctx, "hex");
                                if (setCubeColorFromHex(hex)) {
                                    ctx.getSource().sendFeedback(Text.literal("[PathTracker] Cube color updated to " + hex));
                                    this.pathStorageSessions.setColor(hex);
                                    return 1;
                                } else {
                                    ctx.getSource().sendFeedback(Text.literal("[PathTracker] Invalid color format. Use #RRGGBB or RRGGBB."));
                                    return 0;
                                }
                            })
                        )
                    )
                    // /pathtracker save
                    .then(literal("save")
                        .executes(ctx -> {
                            saveAllPathData();
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Path data saved successfully."));
                            return 1;
                        })
                    )
                    // /pathtracker session list
                    .then(literal("session")
                        .then(literal("current")
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(Text.literal("[PathTracker] Current session: " + pathStorageSessions.getCurrentSession()));
                                return 1;
                            })
                        )
                        .then(literal("list")
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(Text.literal("[PathTracker] Available sessions: " + pathStorageSessions.getSessions()));
                                return 1;
                            })
                        )
                        .then(literal("create")
                            .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String sessionName = StringArgumentType.getString(ctx, "name");
                                    if (pathStorageSessions.getSessions().contains(sessionName)) {
                                        ctx.getSource().sendFeedback(Text.literal("[PathTracker] Session already exists: " + sessionName));
                                        return 0;
                                    }
                                    pathStorageSessions.addNewSession(sessionName);
                                    ctx.getSource().sendFeedback(Text.literal("[PathTracker] Created new session: " + sessionName));
                                    return 1;
                                })
                            )
                        )
                        .then(literal("switch")
                            .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String sessionName = StringArgumentType.getString(ctx, "name");
                                    if (!pathStorageSessions.getSessions().contains(sessionName)) {
                                        ctx.getSource().sendFeedback(Text.literal("[PathTracker] Session not found: " + sessionName));
                                        return 0;
                                    }
                                    this.saveAllPathData();
                                    pathStorageSessions.setCurrentSession(sessionName);
                                    this.visitedPositionsMap = pathStorageSessions.load(sessionName, this.currentMap);
                                    ctx.getSource().sendFeedback(Text.literal("[PathTracker] Switched to session: " + sessionName + " for map: " + this.currentMap));	
                                    for (RegistryKey<World> key : visitedPositionsMap.keySet()) {
                                        System.out.println("[PathTracker] Loaded " + visitedPositionsMap.get(key).size() + " positions for dimension " + key.getValue());
                                    }
                                    return 1;
                                })
                            )
                        )
                    )
                    // /pathtracker depth on|off
                    .then(literal("depth")
                        .then(literal("on").executes(ctx -> {
                            depthOverride = true;
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Depth override enabled."));
                            return 1;
                        }))
                        .then(literal("off").executes(ctx -> {
                            depthOverride = false;
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Depth override disabled."));
                            return 1;
                        }))
                    )
                    .then(literal("transparency")
                        .then(argument("value", IntegerArgumentType.integer(0, 10000))
                            .executes(ctx -> {
                                int value = IntegerArgumentType.getInteger(ctx, "value");
                                pathStorageSessions.setTransparency(value / 10000.0f);
                                ctx.getSource().sendFeedback(Text.literal("[PathTracker] Transparency set to " + value));
                                return 1;
                            })
                        )
                    )
            );
        });

        System.out.println("[PathTracker] onInitializeClient complete.");
    }

    /**
     * Handles client tick events to manage key bindings and track player movement.
     */
    private void onEndClientTick(MinecraftClient client) {
        // Toggle tracking if key pressed
        if (toggleTrackingKey.wasPressed()) {
            trackingEnabled = !trackingEnabled;
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[PathTracker] Tracking: " + (trackingEnabled ? "Enabled" : "Disabled")), false);
            }
        }
        // Toggle rendering if key pressed
        if (toggleRenderingKey.wasPressed()) {
            renderingEnabled = !renderingEnabled;
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[PathTracker] Rendering: " + (renderingEnabled ? "Enabled" : "Disabled")), false);
            }
        }
        if (client.player == null || client.world == null) return;
        // Get current dimension
        RegistryKey<World> currentDimension = client.world.getRegistryKey();
        // Get current map/server name
        String mapName;
        if (client.isIntegratedServerRunning()) {
            IntegratedServer server = client.getServer();
            mapName = server.getSaveProperties().getLevelName();
        } else {
            mapName = client.getCurrentServerEntry().address;
        }

        // Initialize storage for the current dimension if not present
        if (this.currentMap == null || !this.currentMap.equals(mapName)) {
            this.currentMap = mapName;
            this.visitedPositionsMap = pathStorageSessions.load(pathStorageSessions.getCurrentSession(), this.currentMap);
            client.player.sendMessage(Text.literal("[PathTracker] Switched to map: " + this.currentMap + " for session: " + pathStorageSessions.getCurrentSession()), false);
            for (RegistryKey<World> key : visitedPositionsMap.keySet()) {
                System.out.println("[PathTracker] Loaded " + visitedPositionsMap.get(key).size() + " positions for dimension " + key.getValue());
            }
        }

        // If not tracking, skip
        if (!trackingEnabled) return;
        // If no player or world, skip
        if (client.player == null || client.world == null) return;

        // Track the block one behind the player
        BlockPos behindPos = getBlockBehindPlayer(client.player);

        // Get the set for the current dimension
        Set<BlockPos> currentVisited = visitedPositionsMap.get(currentDimension);

        if (!behindPos.equals(lastTrackedPos)) {
            currentVisited.add(behindPos);
            lastTrackedPos = behindPos;
        }
    }

    /**
     * Calculates the block position one block behind the player based on their facing direction.
     *
     * @param player The player entity.
     * @return The BlockPos one block behind the player.
     */
    private BlockPos getBlockBehindPlayer(net.minecraft.entity.player.PlayerEntity player) {
        // Get the direction the player is facing
        Direction facing = player.getHorizontalFacing();

        // Get the block position one block behind the player
        BlockPos currentPos = player.getBlockPos();
        BlockPos behindPos = currentPos.offset(facing.getOpposite());

        return behindPos;
    }

    /**
     * Renders each visited position as a small, translucent cube with a custom color.
     */
    private void onWorldRender(WorldRenderContext context) {
        if (!renderingEnabled) return;

        // Optional: Uncomment for debugging to ensure this method is being called
        // System.out.println("[PathTracker] onWorldRender called, drawing overlays!");

        MatrixStack matrixStack = context.matrixStack();
        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();

        // Use the simple position + color shader
        RenderSystem.setShader(() -> context.gameRenderer().getPositionColorProgram());

        // Enable necessary OpenGL states for transparency
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc(); // Equivalent to GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA


        if (!depthOverride) {
            // Enable depth testing to ensure correct occlusion by opaque blocks
            RenderSystem.enableDepthTest();
            // Prevent the cubes from writing to the depth buffer
            RenderSystem.depthMask(false);
        } else {
            // Disable depth testing to render on top of everything
            RenderSystem.disableDepthTest();
        }

        // Optionally, disable face culling to ensure all faces are rendered
        RenderSystem.disableCull();

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        // Use the simplified vertex format: POSITION_COLOR
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        // Define cube size and offset to center the cube within the block
        float cubeSize = 0.6f;
        float offset = (1.0f - cubeSize) / 2.0f; // Center the smaller cube

        // Get current dimension
        RegistryKey<World> currentDimension = MinecraftClient.getInstance().world.getRegistryKey();

        // Get the set for the current dimension
        Set<BlockPos> currentVisited = visitedPositionsMap.get(currentDimension);
        if (currentVisited == null || currentVisited.isEmpty()) {
            // No positions to render
            tessellator.draw();
            // Re-enable depth writing and face culling after rendering
            if (!depthOverride) {
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(true);
            }
            RenderSystem.enableCull();
            // Optional: Reset shader to default after rendering
            RenderSystem.setShader(GameRenderer::getPositionTexProgram);
            return;
        }

        for (BlockPos pos : currentVisited) {
            double x = pos.getX() - camPos.x;
            double y = pos.getY() - camPos.y;
            double z = pos.getZ() - camPos.z;
            // Skip blocks that are > 256 blocks away
            if (Math.abs(x) > 256 || Math.abs(y) > 256 || Math.abs(z) > 256) {
                continue;
            }
            double xMin = x + offset;
            double xMax = x + offset + cubeSize;
            double yMin = y + offset;
            double yMax = y + offset + cubeSize;
            double zMin = z + offset;
            double zMax = z + offset + cubeSize;

            float red = cubeRed;
            float green = cubeGreen;
            float blue = cubeBlue;
            float alpha = pathStorageSessions.getTransparency();

            // FRONT FACE
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();

            // BACK FACE
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();

            // LEFT FACE
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();

            // RIGHT FACE
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();

            // BOTTOM FACE
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();

            // TOP FACE
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMax)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMin)
                  .color(red, green, blue, alpha)
                  .next();
        }

        tessellator.draw();

        // Re-enable depth writing and face culling after rendering
        if (!depthOverride) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(true);
        }
        RenderSystem.enableCull();

        // Optional: Reset shader to default after rendering
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
    }

    /**
     * Saves all path data for all dimensions.
     */
    private void saveAllPathData() {
        System.out.println("[PathTracker] Saving all path data...");
        // print to console currentSession and mapName for debugging
        System.out.println("[PathTracker] Saving Current session: " + pathStorageSessions.getCurrentSession());
        System.out.println("[PathTracker] Saving Map name: " + this.currentMap);
        pathStorageSessions.save(pathStorageSessions.getCurrentSession(), this.currentMap, visitedPositionsMap);
    }

    /**
     * Parses and sets the overlay color from a hex string (#RRGGBB or RRGGBB).
     *
     * @param rawHex The hex color string.
     * @return True if the color was set successfully, false otherwise.
     */
    private static boolean setCubeColorFromHex(String rawHex) {
        if (rawHex.startsWith("#")) {
            rawHex = rawHex.substring(1);
        }
        if (rawHex.length() != 6) {
            return false;
        }
        try {
            int rgb = Integer.parseInt(rawHex, 16);
            cubeRed   = ((rgb >> 16) & 0xFF) / 255.0f;
            cubeGreen = ((rgb >>  8) & 0xFF) / 255.0f;
            cubeBlue  = ( rgb        & 0xFF) / 255.0f;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
