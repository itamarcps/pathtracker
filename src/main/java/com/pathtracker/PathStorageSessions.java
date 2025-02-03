package com.pathtracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet; // still used for sessions
import java.util.List;
import java.util.Map;

public class PathStorageSessions {
    public static enum Modes {
        DEFAULT,
        GROUPED
    }
    private final Path dataStoragePath;
    private HashSet<String> sessions;
    private String currentSession = "default";
    private String color = "0xFF0000";
    private float transparency = 0.4f;
    private Modes mode = Modes.GROUPED;
    private float thickness = 0.2f;
    private int groupSize = 5;
    private int subdivisions = 16;

    // The file structure for PathStorageSessions:
    //  \CONFIG
    //  └───pathtracer
    //      │   sessions.json
    //      │   settings.json
    //      │
    //      └───default
    //          path_data_MAP_NAME_overworld.bin
    //          path_data_MAP_NAME_the_nether.bin
    //          path_data_MAP_NAME_the_end.bin

    public PathStorageSessions(String storagePath) {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        this.dataStoragePath = configDir.resolve(storagePath);
        if (!Files.exists(dataStoragePath)) {
            try {
                Files.createDirectories(dataStoragePath);
                if (!Files.exists(dataStoragePath.resolve("default"))) {
                    Files.createDirectories(dataStoragePath.resolve("default"));
                    if (!Files.exists(dataStoragePath.resolve("sessions.json"))) {
                        try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve("sessions.json"))) {
                            JsonArray array = new JsonArray();
                            array.add("default");
                            Gson gson = new GsonBuilder().setPrettyPrinting().create();
                            gson.toJson(array, writer);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    if (!Files.exists(dataStoragePath.resolve("settings.json"))) {
                        try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve("settings.json"))) {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("currentSession", "default");
                            obj.addProperty("color", "0xFF0000");
                            obj.addProperty("transparency", 0.65f);
                            obj.addProperty("mode", Modes.GROUPED.toString());
                            obj.addProperty("thickness", 0.2f);
                            obj.addProperty("groupSize", 5);
                            obj.addProperty("subdivisions", 16);
                            Gson gson = new GsonBuilder().setPrettyPrinting().create();
                            gson.toJson(obj, writer);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // Automatically convert any legacy JSON path-data files to the new binary format.
        convertAllJsonToBinary();
        loadSessions();
    }

    private void loadSessions() {
        this.sessions = new HashSet<>();
        try (Reader reader = Files.newBufferedReader(dataStoragePath.resolve("sessions.json"))) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonArray()) {
                return;
            }
            JsonArray array = element.getAsJsonArray();
            for (JsonElement e : array) {
                if (e.isJsonPrimitive()) {
                    this.sessions.add(e.getAsString());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (Reader reader = Files.newBufferedReader(dataStoragePath.resolve("settings.json"))) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                return;
            }
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("currentSession")) {
                this.currentSession = obj.get("currentSession").getAsString();
            }
            if (obj.has("color")) {
                this.color = obj.get("color").getAsString();
            }
            if (obj.has("transparency")) {
                this.transparency = obj.get("transparency").getAsFloat();
            }
            if (obj.has("mode")) {
                this.mode = Modes.valueOf(obj.get("mode").getAsString());
            }
            if (obj.has("thickness")) {
                this.thickness = obj.get("thickness").getAsFloat();
            }
            if (obj.has("groupSize")) {
                this.groupSize = obj.get("groupSize").getAsInt();
            }
            if (obj.has("subdivisions")) {
                this.subdivisions = obj.get("subdivisions").getAsInt();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void dumpSessions() {
        JsonArray array = new JsonArray();
        for (String session : this.sessions) {
            array.add(session);
        }
        try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve("sessions.json"))) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(array, writer);
        } catch (IOException e) {
            e.printStackTrace();
        } 
    }  

    public HashSet<String> getSessions() {
        return this.sessions;
    }

    public void addNewSession(String sessionName) {
        if (!this.sessions.contains(sessionName)) {
            this.sessions.add(sessionName);
            dumpSessions();
        }
    }

    public String getCurrentSession() {
        return this.currentSession;
    }

    public void setCurrentSession(String sessionName) {
        this.currentSession = sessionName;
        dumpSettings();
    }

    public String getColor() {
        return this.color;
    }

    public void setColor(String color) {
        this.color = color;
        dumpSettings();
    }	

    public float getTransparency() {
        return this.transparency;
    }

    public void setTransparency(float transparency) {
        this.transparency = transparency;
        dumpSettings();
    }

    public Modes getMode() {
        return this.mode;
    }

    public void setMode(Modes mode) {
        this.mode = mode;
        dumpSettings();
    }

    public float getThickness() {
        return this.thickness;
    }

    public void setThickness(float thickness) {
        this.thickness = thickness;
        dumpSettings();
    }

    public int getGroupSize() {
        return this.groupSize;
    }

    public void setGroupSize(int groupSize) {
        this.groupSize = groupSize;
        dumpSettings();
    }

    public int getSubdivisions() {
        return this.subdivisions;
    }

    public void setSubdivisions(int subdivisions) {
        this.subdivisions = subdivisions;
        dumpSettings();
    }

    private void dumpSettings() {
        try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve("settings.json"))) {
            JsonObject obj = new JsonObject();
            obj.addProperty("currentSession", this.currentSession);
            obj.addProperty("color", color);
            obj.addProperty("transparency", this.transparency);
            obj.addProperty("mode", this.mode.toString());
            obj.addProperty("thickness", this.thickness);
            obj.addProperty("groupSize", this.groupSize);
            obj.addProperty("subdivisions", this.subdivisions); 
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(obj, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Converts all legacy JSON path-data files to the new binary format.
     * For each session directory, any file matching "path_data_*.json" will be read,
     * converted into a binary file (with extension ".bin"), and then deleted.
     */
    private void convertAllJsonToBinary() {
        try {
            Files.list(dataStoragePath)
                .filter(Files::isDirectory)
                .forEach(sessionDir -> {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionDir, "path_data_*.json")) {
                        for (Path jsonFile : stream) {
                            List<BlockPos> positions = new ArrayList<>();
                            try (Reader reader = Files.newBufferedReader(jsonFile)) {
                                JsonElement element = JsonParser.parseReader(reader);
                                if (element.isJsonArray()) {
                                    JsonArray array = element.getAsJsonArray();
                                    for (JsonElement e : array) {
                                        if (e.isJsonObject()) {
                                            JsonObject obj = e.getAsJsonObject();
                                            int x = obj.get("x").getAsInt();
                                            int y = obj.get("y").getAsInt();
                                            int z = obj.get("z").getAsInt();
                                            positions.add(new BlockPos(x, y, z));
                                        }
                                    }
                                }
                            }
                            // Write out a binary file with the same base name but with a .bin extension.
                            String fileName = jsonFile.getFileName().toString();
                            String binFileName = fileName.substring(0, fileName.lastIndexOf('.')) + ".bin";
                            Path binFile = sessionDir.resolve(binFileName);
                            try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(binFile))) {
                                // Write each BlockPos as 3 ints (4 bytes each, total 12 bytes per block)
                                for (BlockPos pos : positions) {
                                    dos.writeInt(pos.getX());
                                    dos.writeInt(pos.getY());
                                    dos.writeInt(pos.getZ());
                                }
                            }
                            // Delete the old JSON file.
                            Files.delete(jsonFile);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves the path data for each dimension in a binary file.
     * Each BlockPos is serialized as 3 ints (4 bytes each, 12 bytes total per block).
     */
    public void save(String sessionName, String mapName, Map<RegistryKey<World>, List<BlockPos>> visitedPositionsMap) {
        if (!this.sessions.contains(sessionName)) {
            this.sessions.add(sessionName);
            dumpSessions();
        }
        for (Map.Entry<RegistryKey<World>, List<BlockPos>> entry : visitedPositionsMap.entrySet()) {
            RegistryKey<World> dimensionKey = entry.getKey();
            List<BlockPos> positions = entry.getValue();
            String dimensionName = dimensionKey.getValue().toString().replace(':', '_').replace('/', '_');
            String fileName = "path_data_" + mapName + "_" + dimensionName + ".bin";
            Path outFile = dataStoragePath.resolve(sessionName).resolve(fileName);
            if (!Files.exists(dataStoragePath.resolve(sessionName))) {
                try {
                    Files.createDirectories(dataStoragePath.resolve(sessionName));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(outFile))) {
                // Write each BlockPos as 3 ints (12 bytes per position)
                for (BlockPos pos : positions) {
                    dos.writeInt(pos.getX());
                    dos.writeInt(pos.getY());
                    dos.writeInt(pos.getZ());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Loads the path data for each dimension from the binary files.
     */
    public Map<RegistryKey<World>, List<BlockPos>> load(String sessionName, String mapName) {
        Map<RegistryKey<World>, List<BlockPos>> visitedPositionsMap = new HashMap<>();
        String filePrefix = "path_data_" + mapName + "_";
        
        // Overworld
        Path overworldFile = dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_overworld.bin");
        visitedPositionsMap.put(World.OVERWORLD, readBlockPosBinary(overworldFile));
        
        // Nether
        Path netherFile = dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_the_nether.bin");
        visitedPositionsMap.put(World.NETHER, readBlockPosBinary(netherFile));
        
        // End
        Path endFile = dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_the_end.bin");
        visitedPositionsMap.put(World.END, readBlockPosBinary(endFile));
        
        return visitedPositionsMap;
    }

    /**
     * Helper method to read a list of BlockPos from a binary file.
     * Reads in 12 bytes at a time (3 ints) until the end of the file.
     */
    private List<BlockPos> readBlockPosBinary(Path file) {
        List<BlockPos> positions = new ArrayList<>();
        if (Files.exists(file)) {
            try (DataInputStream dis = new DataInputStream(Files.newInputStream(file))) {
                while (dis.available() >= 12) {
                    int x = dis.readInt();
                    int y = dis.readInt();
                    int z = dis.readInt();
                    positions.add(new BlockPos(x, y, z));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return positions;
    }
}
