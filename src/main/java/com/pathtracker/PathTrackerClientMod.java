package com.pathtracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    // Whether we’re rendering the path overlay
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

    // Map to hold visited positions per dimension, now as an ordered List
    private Map<RegistryKey<World>, List<BlockPos>> visitedPositionsMap = new HashMap<>();
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

        // Initialize storage for the current map if not present or if map switched
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

        // Get the block one behind the player
        BlockPos behindPos = getBlockBehindPlayer(client.player);

        // Ensure we have a list for the current dimension
        visitedPositionsMap.computeIfAbsent(currentDimension, key -> new ArrayList<>());

        if (!behindPos.equals(lastTrackedPos)) {
            visitedPositionsMap.get(currentDimension).add(behindPos);
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
     * Renders the visited path as a continuous thick line connecting the centers of visited blocks.
     */
    private void onWorldRender(WorldRenderContext context) {
        if (!renderingEnabled) return;

        MatrixStack matrixStack = context.matrixStack();
        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();

        // Use the simple position + color shader
        RenderSystem.setShader(() -> context.gameRenderer().getPositionColorProgram());

        // Enable blending for transparency
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        if (!depthOverride) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
        } else {
            RenderSystem.disableDepthTest();
        }

        RenderSystem.disableCull();

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        // Begin drawing quads (each quad will represent a segment of the thick line)
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        // Get current dimension
        RegistryKey<World> currentDimension = MinecraftClient.getInstance().world.getRegistryKey();

        List<BlockPos> currentVisited = visitedPositionsMap.get(currentDimension);
        if (currentVisited == null || currentVisited.size() < 2) {
            tessellator.draw();
            if (!depthOverride) {
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(true);
            }
            RenderSystem.enableCull();
            RenderSystem.setShader(GameRenderer::getPositionTexProgram);
            return;
        }

        // For each consecutive pair, draw a quad that connects the two block centers.
        // We assume the “center” of a block is at (x+0.5, y+0.5, z+0.5).
        final double maxConnectionDistance = 1.5; // If points are farther apart, skip joining them.
        final double halfThickness = 0.1 / 2.0;     // 10% of a block width

        for (int i = 0; i < currentVisited.size() - 1; i++) {
            BlockPos posA = currentVisited.get(i);
            BlockPos posB = currentVisited.get(i + 1);

            Vec3d A = new Vec3d(posA.getX() + 0.5, posA.getY() + 0.5, posA.getZ() + 0.5);
            Vec3d B = new Vec3d(posB.getX() + 0.5, posB.getY() + 0.5, posB.getZ() + 0.5);

            if (A.distanceTo(B) > maxConnectionDistance) {
                // If the two points are not adjacent, do not connect them.
                continue;
            }

            Vec3d mid = A.add(B).multiply(0.5);
            Vec3d tangent = B.subtract(A).normalize();
            Vec3d toCam = mid.subtract(camPos).normalize();
            Vec3d perpendicular = tangent.crossProduct(toCam).normalize();
            Vec3d offset = perpendicular.multiply(halfThickness);

            // Compute the four corners of the quad:
            Vec3d A_left  = A.subtract(offset);
            Vec3d A_right = A.add(offset);
            Vec3d B_left  = B.subtract(offset);
            Vec3d B_right = B.add(offset);

            float alpha = pathStorageSessions.getTransparency();

            // Subtract the camera position for each vertex
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)(A_left.x - camPos.x), (float)(A_left.y - camPos.y), (float)(A_left.z - camPos.z))
                  .color(cubeRed, cubeGreen, cubeBlue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)(A_right.x - camPos.x), (float)(A_right.y - camPos.y), (float)(A_right.z - camPos.z))
                  .color(cubeRed, cubeGreen, cubeBlue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)(B_right.x - camPos.x), (float)(B_right.y - camPos.y), (float)(B_right.z - camPos.z))
                  .color(cubeRed, cubeGreen, cubeBlue, alpha)
                  .next();
            buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)(B_left.x - camPos.x), (float)(B_left.y - camPos.y), (float)(B_left.z - camPos.z))
                  .color(cubeRed, cubeGreen, cubeBlue, alpha)
                  .next();
        }

        tessellator.draw();

        if (!depthOverride) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(true);
        }
        RenderSystem.enableCull();

        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
    }

    /**
     * Saves all path data for all dimensions.
     */
    private void saveAllPathData() {
        System.out.println("[PathTracker] Saving all path data...");
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
