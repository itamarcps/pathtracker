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
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.ShaderProgramKeys;
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

    // Map to hold visited positions per dimension, stored as an ordered list.
    private Map<RegistryKey<World>, List<BlockPos>> visitedPositionsMap = new HashMap<>();
    // Storage for path data (for everything)
    PathStorageSessions pathStorageSessions = new PathStorageSessions("pathtracer");
    private BlockPos lastTrackedPos = null;
    private String currentMap = null;

    @Override
    public void onInitializeClient() {
        System.out.println("[PathTracker] onInitializeClient called!");

        // Load color settings from storage...
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
                    // /pathtracker mode default|grouped
                    .then(literal("mode")
                        .then(literal("default").executes(ctx -> {
                            pathStorageSessions.setMode(PathStorageSessions.Modes.DEFAULT);
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Mode set to DEFAULT."));
                            return 1;
                        }))
                        .then(literal("grouped").executes(ctx -> {
                            pathStorageSessions.setMode(PathStorageSessions.Modes.GROUPED);
                            ctx.getSource().sendFeedback(Text.literal("[PathTracker] Mode set to GROUPED."));
                            return 1;
                        }))
                    )
                    // /pathtracker thickness <value>
                    .then(literal("thickness")
                        .then(argument("value", IntegerArgumentType.integer(1, 100))
                            .executes(ctx -> {
                                int value = IntegerArgumentType.getInteger(ctx, "value");
                                pathStorageSessions.setThickness(value / 100.0f);
                                ctx.getSource().sendFeedback(Text.literal("[PathTracker] Thickness set to " + value));
                                return 1;
                            })
                        )
                    )
                    // /pathtracker groupsize <value>
                    .then(literal("groupsize")
                        .then(argument("value", IntegerArgumentType.integer(1, 10000))
                            .executes(ctx -> {
                                int value = IntegerArgumentType.getInteger(ctx, "value");
                                pathStorageSessions.setGroupSize(value);
                                ctx.getSource().sendFeedback(Text.literal("[PathTracker] Group size set to " + value));
                                return 1;
                            })
                        )
                    )
                    // /pathtracker subdivisions <value>
                    .then(literal("subdivisions")
                        .then(argument("value", IntegerArgumentType.integer(1, 10000))
                            .executes(ctx -> {
                                int value = IntegerArgumentType.getInteger(ctx, "value");
                                pathStorageSessions.setSubdivisions(value);
                                ctx.getSource().sendFeedback(Text.literal("[PathTracker] Subdivisions set to " + value));
                                return 1;
                            })
                        )
                    )
                    // /pathtracker renderdistance <value>
                    .then(literal("renderdistance")
                        .then(argument("value", IntegerArgumentType.integer(1, 10000))
                            .executes(ctx -> {
                                int value = IntegerArgumentType.getInteger(ctx, "value");
                                pathStorageSessions.setRenderDistance(value);
                                ctx.getSource().sendFeedback(Text.literal("[PathTracker] Render distance set to " + value));
                                return 1;
                            })
                        )
                    )
            );
        });

        System.out.println("[PathTracker] onInitializeClient complete.");
    }

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

        // Initialize storage for the current map if not present or if the map has switched
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
        if (client.player == null || client.world == null) return;

        // Track the block one behind the player
        BlockPos behindPos = getBlockBehindPlayer(client.player);

        // Ensure we have a list for the current dimension
        visitedPositionsMap.computeIfAbsent(currentDimension, key -> new ArrayList<>());

        if (!behindPos.equals(lastTrackedPos)) {
            visitedPositionsMap.get(currentDimension).add(behindPos);
            lastTrackedPos = behindPos;
        }
    }

    private BlockPos getBlockBehindPlayer(net.minecraft.entity.player.PlayerEntity player) {
        Direction facing = player.getHorizontalFacing();
        BlockPos currentPos = player.getBlockPos();
        return currentPos.offset(facing.getOpposite());
    }

    /**
     * Renders the visited path as a smooth, thick curved line.
     */
    private void onWorldRender(WorldRenderContext context) {
        if (!renderingEnabled) return;
    
        MatrixStack matrixStack = context.matrixStack();
        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
    
        // Use the simple position+color shader.
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
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
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        boolean emptyBuffer = true;
    
        // Get the current dimension and its visited block centers.
        RegistryKey<World> currentDimension = MinecraftClient.getInstance().world.getRegistryKey();
        List<BlockPos> visited = visitedPositionsMap.get(currentDimension);
        if (visited == null || visited.size() == 0) {
            if (!depthOverride) {
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(true);
            }
            RenderSystem.enableCull();
            RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX_COLOR);
            return;
        }
    
        // Split visited positions into segments whenever the next block isn’t a neighbor.
        // (We add all segments—even those with a single block—to allow cube rendering.)
        List<List<BlockPos>> segments = new ArrayList<>();
        List<BlockPos> currentSegment = new ArrayList<>();
        currentSegment.add(visited.get(0));
        int renderDistance = this.pathStorageSessions.getRenderDistance();
        for (int i = 1; i < visited.size(); i++) {
            BlockPos current = visited.get(i);
            // If the current block is > renderDistance blocks away from the player, we dont need to render
            // because its a waste of resources.
            // simply x > renderDistance or y > renderDistance or z > renderDistance, not really a distance check.
            if (Math.abs(current.getX() - camPos.x) > renderDistance || Math.abs(current.getY() - camPos.y) > renderDistance || Math.abs(current.getZ() - camPos.z) > renderDistance) {
                continue;
            }
            BlockPos prev = visited.get(i - 1);
            if (areNeighbors(prev, current)) {
                currentSegment.add(current);
            } else {
                segments.add(currentSegment);
                currentSegment = new ArrayList<>();
                currentSegment.add(current);
            }
        }
        segments.add(currentSegment);
    
        final int subdivisions = this.pathStorageSessions.getSubdivisions();
        final double thickness = this.pathStorageSessions.getThickness();
        float alpha = pathStorageSessions.getTransparency();
    
        // Process each segment.
        for (List<BlockPos> segment : segments) {
            // If the segment contains less than two blocks, render using the old cube code.
            if (segment.size() < 2) {
                // Define cube size and offset to center the cube within the block
                float cubeSize = 0.6f;
                float halfSize = cubeSize / 2.0f;  // 0.3
                
                for (BlockPos pos : segment) {
                    // Block center in world space
                    double centerX = pos.getX() + 0.5;
                    double centerY = pos.getY() + 0.5;
                    double centerZ = pos.getZ() + 0.5;
                
                    // Convert to camera-relative coordinates
                    double x = centerX - camPos.x;
                    double y = centerY - camPos.y;
                    double z = centerZ - camPos.z;
                
                    // Now define the bounding box around that point
                    double xMin = x - halfSize;
                    double xMax = x + halfSize;
                    double yMin = y - halfSize;
                    double yMax = y + halfSize;
                    double zMin = z - halfSize;
                    double zMax = z + halfSize;
    
                    float red = cubeRed;
                    float green = cubeGreen;
                    float blue = cubeBlue;
                    float cubeAlpha = alpha / 2.0f;
                    emptyBuffer = false;
                    // Render all 6 faces of the cube:
                    // FRONT FACE
                    buffer.vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
    
                    // LEFT FACE
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
    
                    // RIGHT FACE
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
    
                    // BOTTOM FACE
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMin, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMin, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
    
                    // TOP FACE
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMax)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMax, (float)yMax, (float)zMin)
                          .color(red, green, blue, cubeAlpha)
                    .vertex(matrixStack.peek().getPositionMatrix(), (float)xMin, (float)yMax, (float)zMin)
                          .color(red, green, blue, cubeAlpha);
                }
                continue; // Move to the next segment.
            }
    
            // Otherwise, for segments with two or more blocks, use spline (quad strip) rendering.
            List<Vec3d> centers = new ArrayList<>();
            for (BlockPos pos : segment) {
                centers.add(new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            }
            List<Vec3d> curvePoints = new ArrayList<>();
            switch (pathStorageSessions.getMode()) {
                case DEFAULT:
                    // Generate a smooth curve through the centers using a Catmull–Rom spline.
                    for (int i = 0; i < centers.size() - 1; i++) {
                        Vec3d p0 = (i == 0) ? centers.get(i) : centers.get(i - 1);
                        Vec3d p1 = centers.get(i);
                        Vec3d p2 = centers.get(i + 1);
                        Vec3d p3 = (i + 2 < centers.size()) ? centers.get(i + 2) : centers.get(i + 1);
                        for (int j = 0; j < subdivisions; j++) {
                            double t = j / (double) subdivisions;
                            Vec3d pt = catmullRom(p0, p1, p2, p3, t);
                            curvePoints.add(pt);
                        }
                    }
                    // Ensure the final center is added.
                    curvePoints.add(centers.get(centers.size() - 1));
                    break;
                case GROUPED:
                    List<Vec3d> groupedCenters = new ArrayList<>();
                    int groupSize = pathStorageSessions.getGroupSize();
                    for (int i = 0; i < centers.size(); i += groupSize) {
                        int end = Math.min(i + groupSize, centers.size());
                        double sumX = 0, sumY = 0, sumZ = 0;
                        for (int j = i; j < end; j++) {
                            Vec3d p = centers.get(j);
                            sumX += p.x;
                            sumY += p.y;
                            sumZ += p.z;
                        }
                        double count = end - i;
                        groupedCenters.add(new Vec3d(sumX / count, sumY / count, sumZ / count));
                    }
                    // --- End grouping ---
                    if (groupedCenters.size() < 2) continue;
                    for (int i = 0; i < groupedCenters.size() - 1; i++) {
                        Vec3d p0 = (i == 0) ? groupedCenters.get(i) : groupedCenters.get(i - 1);
                        Vec3d p1 = groupedCenters.get(i);
                        Vec3d p2 = groupedCenters.get(i + 1);
                        Vec3d p3 = (i + 2 < groupedCenters.size()) ? groupedCenters.get(i + 2) : groupedCenters.get(i + 1);
                        for (int j = 0; j < subdivisions; j++) {
                            double t = j / (double) subdivisions;
                            Vec3d pt = catmullRom(p0, p1, p2, p3, t);
                            curvePoints.add(pt);
                        }
                    }
                    // Ensure the final grouped center is added.
                    curvePoints.add(groupedCenters.get(groupedCenters.size() - 1));
                    break;
            }
    
            // Build a quad strip along the smooth curve for this segment.
            for (int i = 0; i < curvePoints.size() - 1; i++) {
                emptyBuffer = false;
                Vec3d current = curvePoints.get(i);
                Vec3d next = curvePoints.get(i + 1);
                Vec3d tangent = next.subtract(current).normalize();
                // Compute a perpendicular vector to the tangent (facing away from the camera)
                Vec3d toCam = current.subtract(camPos).normalize();
                Vec3d perp = tangent.crossProduct(toCam).normalize();
                Vec3d offsetVec = perp.multiply(thickness);
                Vec3d currentLeft = current.subtract(offsetVec);
                Vec3d currentRight = current.add(offsetVec);
                Vec3d nextLeft = next.subtract(offsetVec);
                Vec3d nextRight = next.add(offsetVec);
    
                buffer.vertex(matrixStack.peek().getPositionMatrix(),
                    (float)(currentLeft.x - camPos.x),
                    (float)(currentLeft.y - camPos.y),
                    (float)(currentLeft.z - camPos.z))
                        .color(cubeRed, cubeGreen, cubeBlue, alpha)
                    .vertex(matrixStack.peek().getPositionMatrix(),
                    (float)(currentRight.x - camPos.x),
                    (float)(currentRight.y - camPos.y),
                    (float)(currentRight.z - camPos.z))
                        .color(cubeRed, cubeGreen, cubeBlue, alpha)
                    .vertex(matrixStack.peek().getPositionMatrix(),
                    (float)(nextRight.x - camPos.x),
                    (float)(nextRight.y - camPos.y),
                    (float)(nextRight.z - camPos.z))
                        .color(cubeRed, cubeGreen, cubeBlue, alpha)
                    .vertex(matrixStack.peek().getPositionMatrix(),
                    (float)(nextLeft.x - camPos.x),
                    (float)(nextLeft.y - camPos.y),
                    (float)(nextLeft.z - camPos.z))
                        .color(cubeRed, cubeGreen, cubeBlue, alpha);
            }
        }
    
        if (!emptyBuffer) {
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        }
        if (!depthOverride) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(true);
        }
        RenderSystem.enableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX_COLOR);

    }
    
    

    /**
     * Computes a point on a Catmull–Rom spline given four control points and a parameter t in [0, 1].
     *
     * The formula is:  
     * 0.5 * [2P1 + (P2 – P0)t + (2P0 – 5P1 + 4P2 – P3)t² + (–P0 + 3P1 – 3P2 + P3)t³]
     */
    private Vec3d catmullRom(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        Vec3d term1 = p1.multiply(2.0);
        Vec3d term2 = p2.subtract(p0).multiply(t);
        Vec3d term3 = p0.multiply(2.0).subtract(p1.multiply(5.0)).add(p2.multiply(4.0)).subtract(p3).multiply(t2);
        Vec3d term4 = p0.negate().add(p1.multiply(3.0)).subtract(p2.multiply(3.0)).add(p3).multiply(t3);
        return term1.add(term2).add(term3).add(term4).multiply(0.5);
    }

    /**
     * Returns true if the two block positions are adjacent in every axis.
     */
    private boolean areNeighbors(BlockPos a, BlockPos b) {
        // 2 Steps in any direction (including diagonals)
        return Math.abs(a.getX() - b.getX()) <= 2 &&
               Math.abs(a.getY() - b.getY()) <= 2 &&
               Math.abs(a.getZ() - b.getZ()) <= 2;
    }

    private void saveAllPathData() {
        System.out.println("[PathTracker] Saving all path data...");
        System.out.println("[PathTracker] Saving Current session: " + pathStorageSessions.getCurrentSession());
        System.out.println("[PathTracker] Saving Map name: " + this.currentMap);
        pathStorageSessions.save(pathStorageSessions.getCurrentSession(), this.currentMap, visitedPositionsMap);
    }

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
