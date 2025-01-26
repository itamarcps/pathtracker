package com.pathtracker;

import net.fabricmc.api.ModInitializer;

public class PathTrackerMod implements ModInitializer {

    @Override
    public void onInitialize() {
        // This is never called because the mod JSON doesn't list a "main" entrypoint.
        System.out.println("[PathTracker] This common initializer is never used in a pure client mod.");
    }
}
