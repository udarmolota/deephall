package com.zomdroid.game;

import com.zomdroid.C;

import java.util.ArrayList;

public class PresetManager {
    private static final ArrayList<InstallationPreset> presets = new ArrayList<>();

    static {
        // Deephall, our fork of Mountaincore: one fat jar, no FMOD, no java agent, and no x86
        // libraries to emulate. The LWJGL classes are shipped beside the game rather than taken
        // from the fat jar, so that the Java side is the same 3.3.6 as the natives we load; being
        // first on the class path is what makes that stick.
        presets.add(new InstallationPreset.Builder()
                .setName("Deephall")
                .setBuildVersion("deephall")
                .setGameFileMarker("mountaincore.jar")
                .setClassPathArray(new String[]{
                        "lwjgl336/lwjgl-3.3.6.jar",
                        "lwjgl336/lwjgl-glfw-3.3.6.jar",
                        "lwjgl336/lwjgl-jemalloc-3.3.6.jar",
                        "lwjgl336/lwjgl-openal-3.3.6.jar",
                        "lwjgl336/lwjgl-opengl-3.3.6.jar",
                        "lwjgl336/lwjgl-stb-3.3.6.jar",
                        "mountaincore.jar",
                        "."
                })
                .setLibraryPathArray(new String[]{
                        C.deps.LIBS_LWJGL_336,
                        C.deps.LIBS_ANDROID_ARM64_v8a
                })
                // Nothing of Deephall runs under box64, but the launcher's linker hook starts
                // box64 before the JVM whatever the game, and box64 cannot come up without its
                // x86_64 glibc and JNI wrapper. Without this the JVM is never created at all.
                .setLibraryPathForEmulationArray(new String[]{
                        C.deps.LIBS_LINUX_X86_64
                })
                // One window, no splash screen. See DesktopLauncher; sound comes from libopenal.so (GameLauncher).
                .setExtraJvmArgs(new String[]{"-Dmountaincore.android=true"})
                .setMainClassName("technology/rocketjump/mountaincore/launcher/DesktopLauncher")
                .build()
        );
    }

    public static ArrayList<InstallationPreset> getPresets() {
        return presets;
    }

    /** The preset registered under that name, or null. */
    public static InstallationPreset findByName(String name) {
        for (InstallationPreset preset : presets) {
            if (preset.name.equals(name)) return preset;
        }
        return null;
    }

    /** Indices into {@link #getPresets()}: the order the presets are registered in above. */
    public static final int PRESET_DEEPHALL = 0;

    /**
     * Which preset a set of game files calls for, from their paths: ZIP entry names, or paths
     * relative to an extracted game folder. Tolerant of wrapper folders. -1 when nothing in them
     * belongs to a game the launcher knows.
     * <pre>
     *   mountaincore.jar         -> Deephall
     * </pre>
     */
    public static int detectPresetIndex(Iterable<String> paths) {
        for (String path : paths) {
            // Deephall ships as a single fat jar with that name.
            if (isDeephallJar(path)) return PRESET_DEEPHALL;
        }
        return -1;
    }

    private static boolean isDeephallJar(String name) {
        String n = name.replace('\\', '/');
        return n.equals("mountaincore.jar") || n.endsWith("/mountaincore.jar");
    }
}
