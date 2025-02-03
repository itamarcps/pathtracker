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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet; // still used for sessions
import java.util.List;
import java.util.Map;


public class PathStorageSessions {
    static enum Modes {
        DEFAULT,
        GROUPED
    }
    private final Path dataStoragePath;
    private HashSet<String> sessions;
    private String currentSession = "default";
    private String color = "0xFF0000";
    private float transparency = 0.4f;
    private Modes mode = Modes.DEFAULT;

    // The file structure for PathStorageSessions:
    //  \CONFIG
    //  └───pathtracer
    //      │   sessions.json
    //      │   settings.json
    //      │
    //      └───default
    //          path_data_MAP_NAME_overworld.json
    //          path_data_MAP_NAME_the_nether.json
    //          path_data_MAP_NAME_the_end.json
    
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
                            obj.addProperty("mode", Modes.DEFAULT.toString());
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

    private void dumpSettings() {
        try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve("settings.json"))) {
            JsonObject obj = new JsonObject();
            obj.addProperty("currentSession", this.currentSession);
            obj.addProperty("color", color);
            obj.addProperty("transparency", this.transparency);
            obj.addProperty("mode", this.mode.toString());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(obj, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves the path data for each dimension. The positions are saved as a JSON array in the order they were added.
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
            String fileName = "path_data_" + mapName + "_" + dimensionName + ".json";
            JsonArray array = new JsonArray();
            for (BlockPos pos : positions) {
                JsonObject obj = new JsonObject();
                obj.addProperty("x", pos.getX());
                obj.addProperty("y", pos.getY());
                obj.addProperty("z", pos.getZ());
                array.add(obj);
            }
            if (!Files.exists(dataStoragePath.resolve(sessionName))) {
                try {
                    Files.createDirectories(dataStoragePath.resolve(sessionName));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve(sessionName).resolve(fileName))) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(array, writer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }        
    }

    /**
     * Loads the path data for each dimension as an ordered list of BlockPos.
     */
    public Map<RegistryKey<World>, List<BlockPos>> load(String sessionName, String mapName) {
        Map<RegistryKey<World>, List<BlockPos>> visitedPositionsMap = new HashMap<>();
        // For each of the three dimensions, attempt to load the corresponding file.
        String filePrefix = "path_data_" + mapName + "_";
        
        // Overworld
        if (Files.exists(dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_overworld.json"))) {
            try (Reader reader = Files.newBufferedReader(dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_overworld.json"))){
                JsonElement element = JsonParser.parseReader(reader);
                List<BlockPos> loadedPositions = new ArrayList<>();
                if (element.isJsonArray()) {
                    JsonArray array = element.getAsJsonArray();
                    for (JsonElement e : array) {
                        if (e.isJsonObject()) {
                            JsonObject obj = e.getAsJsonObject();
                            int x = obj.get("x").getAsInt();
                            int y = obj.get("y").getAsInt();
                            int z = obj.get("z").getAsInt();
                            loadedPositions.add(new BlockPos(x, y, z));
                        }
                    }
                }
                visitedPositionsMap.put(World.OVERWORLD, loadedPositions);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            visitedPositionsMap.put(World.OVERWORLD, new ArrayList<>());
        }
        
        // Nether
        if (Files.exists(dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_the_nether.json"))) {
            try (Reader reader = Files.newBufferedReader(dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_the_nether.json"))){
                JsonElement element = JsonParser.parseReader(reader);
                List<BlockPos> loadedPositions = new ArrayList<>();
                if (element.isJsonArray()) {
                    JsonArray array = element.getAsJsonArray();
                    for (JsonElement e : array) {
                        if (e.isJsonObject()) {
                            JsonObject obj = e.getAsJsonObject();
                            int x = obj.get("x").getAsInt();
                            int y = obj.get("y").getAsInt();
                            int z = obj.get("z").getAsInt();
                            loadedPositions.add(new BlockPos(x, y, z));
                        }
                    }
                }
                visitedPositionsMap.put(World.NETHER, loadedPositions);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            visitedPositionsMap.put(World.NETHER, new ArrayList<>());
        }
        
        // End
        if (Files.exists(dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_the_end.json"))) {
            try (Reader reader = Files.newBufferedReader(dataStoragePath.resolve(sessionName).resolve(filePrefix + "minecraft_the_end.json"))){
                JsonElement element = JsonParser.parseReader(reader);
                List<BlockPos> loadedPositions = new ArrayList<>();
                if (element.isJsonArray()) {
                    JsonArray array = element.getAsJsonArray();
                    for (JsonElement e : array) {
                        if (e.isJsonObject()) {
                            JsonObject obj = e.getAsJsonObject();
                            int x = obj.get("x").getAsInt();
                            int y = obj.get("y").getAsInt();
                            int z = obj.get("z").getAsInt();
                            loadedPositions.add(new BlockPos(x, y, z));
                        }
                    }
                }
                visitedPositionsMap.put(World.END, loadedPositions);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else { 
            visitedPositionsMap.put(World.END, new ArrayList<>());
        }
        return visitedPositionsMap;
    }
}
