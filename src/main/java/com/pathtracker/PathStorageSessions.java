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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;


public class PathStorageSessions {
    private final Path dataStoragePath;
    private Set<String> sessions;
    private String currentSession = "default";
    private String color = "0xFF0000";

    // The file structure for PathStorageSessions are the following
    //  \CONFIG
    //  └───pathtracer
    //      │   sessions.json
    //      │
    //      └───default
    //          path_data_MAP_NAME_overworld.json
    //          path_data_MAP_NAME_the_nether.json
    //          path_data_MAP_NAME_the_end.json
    
    // Create a new PathStorageSessions object with the given storage path
    public PathStorageSessions(String storagePath) {
        // The config folder is a convenient place to store user-specific data
        Path configDir = FabricLoader.getInstance().getConfigDir();
        // Create a new directory for the mod if it doesn't exist
        this.dataStoragePath = configDir.resolve(storagePath);
        // Make it a directory if it doesn't exist
        if (!Files.exists(dataStoragePath)) {
            try {
                Files.createDirectories(dataStoragePath);
                // also create the /default folder if it doesn't exist
                if (!Files.exists(dataStoragePath.resolve("default"))) {
                    Files.createDirectories(dataStoragePath.resolve("default"));
                    // initialize session.json with an string array containing "default"
                    try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve("sessions.json"))) {
                        JsonArray array = new JsonArray();
                        array.add("default");
                        Gson gson = new GsonBuilder().setPrettyPrinting().create();
                        gson.toJson(array, writer);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    // Also initialize the currentSession.json with "default"
                    try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve("settings.json"))) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("currentSession", "default");
                        obj.addProperty("color", "0xFF0000");
                        Gson gson = new GsonBuilder().setPrettyPrinting().create();
                        gson.toJson(obj, writer);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        loadSessions();
    }

    // Load all the sessions from the sessions.json into an array, only used by constructor.
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
        // Also load the current session and color
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
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Dump all the sessions to json.
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

    public Set<String> getSessions() {
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
    }

    public String getColor() {
        return this.color;
    }

    public void setColor(String color) {
        this.color = color;
        try (Writer writer = Files.newBufferedWriter(dataStoragePath.resolve("settings.json"))) {
            JsonObject obj = new JsonObject();
            obj.addProperty("currentSession", this.currentSession);
            obj.addProperty("color", color);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(obj, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }	

    public void save(String sessionName, String mapName, Map<RegistryKey<World>, Set<BlockPos>> visitedPositionsMap) {
        if (!this.sessions.contains(sessionName)) {
            this.sessions.add(sessionName);
            dumpSessions();
        }
        for (Map.Entry<RegistryKey<World>, Set<BlockPos>> entry : visitedPositionsMap.entrySet()) {
            RegistryKey<World> dimensionKey = entry.getKey();
            Set<BlockPos> positions = entry.getValue();
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
            // If the session folder doesn't exist, create it
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

    public Map<RegistryKey<World>, Set<BlockPos>> load(String sessionName, String mapName) {
        Map<RegistryKey<World>, Set<BlockPos>> visitedPositionsMap = new HashMap<>();
        for (String session : this.sessions) {
            String fileName = "path_data_" + mapName + "_";
            if (session.equals(sessionName)) {
                // Check if the session folder exists
                if (!Files.exists(dataStoragePath.resolve(session))) {
                    visitedPositionsMap.put(World.OVERWORLD, new HashSet<>());	
                    visitedPositionsMap.put(World.NETHER, new HashSet<>());
                    visitedPositionsMap.put(World.END, new HashSet<>());
                    return visitedPositionsMap;
                } 
                // Check if the files exist
                if (Files.exists(dataStoragePath.resolve(session).resolve(fileName + "minecraft_overworld.json"))) {
                    try (Reader reader = Files.newBufferedReader(dataStoragePath.resolve(session).resolve(fileName + "minecraft_overworld.json"))){
                        JsonElement element = JsonParser.parseReader(reader);
                        if (!element.isJsonArray()) {
                            return visitedPositionsMap;
                        }
                        JsonArray array = element.getAsJsonArray();
                        Set<BlockPos> loadedPositions = new HashSet<>();
                        for (JsonElement e : array) {
                            if (e.isJsonObject()) {
                                JsonObject obj = e.getAsJsonObject();
                                int x = obj.get("x").getAsInt();
                                int y = obj.get("y").getAsInt();
                                int z = obj.get("z").getAsInt();
                                loadedPositions.add(new BlockPos(x, y, z));
                            }
                        }
                        visitedPositionsMap.put(World.OVERWORLD, loadedPositions);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    visitedPositionsMap.put(World.OVERWORLD, new HashSet<>());	
                }
                if (Files.exists(dataStoragePath.resolve(session).resolve(fileName + "minecraft_the_nether.json"))){
                    try (Reader reader = Files.newBufferedReader(dataStoragePath.resolve(session).resolve(fileName + "minecraft_the_nether.json"))){
                        JsonElement element = JsonParser.parseReader(reader);
                        if (!element.isJsonArray()) {
                            return visitedPositionsMap;
                        }
                        JsonArray array = element.getAsJsonArray();
                        Set<BlockPos> loadedPositions = new HashSet<>();
                        for (JsonElement e : array) {
                            if (e.isJsonObject()) {
                                JsonObject obj = e.getAsJsonObject();
                                int x = obj.get("x").getAsInt();
                                int y = obj.get("y").getAsInt();
                                int z = obj.get("z").getAsInt();
                                loadedPositions.add(new BlockPos(x, y, z));
                            }
                        }
                        visitedPositionsMap.put(World.NETHER, loadedPositions);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    visitedPositionsMap.put(World.NETHER, new HashSet<>());
                }
                if (Files.exists(dataStoragePath.resolve(session).resolve(fileName + "minecraft_the_end.json"))){
                    try (Reader reader = Files.newBufferedReader(dataStoragePath.resolve(session).resolve(fileName + "minecraft_the_end.json"))){
                        JsonElement element = JsonParser.parseReader(reader);
                        if (!element.isJsonArray()) {
                            return visitedPositionsMap;
                        }
                        JsonArray array = element.getAsJsonArray();
                        Set<BlockPos> loadedPositions = new HashSet<>();
                        for (JsonElement e : array) {
                            if (e.isJsonObject()) {
                                JsonObject obj = e.getAsJsonObject();
                                int x = obj.get("x").getAsInt();
                                int y = obj.get("y").getAsInt();
                                int z = obj.get("z").getAsInt();
                                loadedPositions.add(new BlockPos(x, y, z));
                            }
                        }
                        visitedPositionsMap.put(World.END, loadedPositions);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else { 
                    visitedPositionsMap.put(World.END, new HashSet<>());
                }
            }
        }
        return visitedPositionsMap;
    }
}
    
