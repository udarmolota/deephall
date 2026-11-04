package com.zomdroid;

import android.system.ErrnoException;
import android.system.Os;
import android.view.Surface;
import android.util.Log;

import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.input.InputControlsView;
import com.zomdroid.game.GameInstance;
import com.zomdroid.BuildConfig;

import java.io.File;
import java.util.ArrayList;

public class GameLauncher {
    public static void launch(GameInstance gameInstance) throws ErrnoException {
        // An instance copies its preset once, when it is created. The preset is still moving with
        // every launcher build, so take the current one at each launch and let a fix to the preset
        // reach the instance that already exists.
        com.zomdroid.game.InstallationPreset current =
                com.zomdroid.game.PresetManager.findByName(gameInstance.getPresetName());
        if (current != null) gameInstance.applyPreset(current);
        // Everything the launch path used to read from the global preferences now belongs to this
        // instance. Read once: InstanceSettings is a view over shared prefs, not a snapshot.
        final com.zomdroid.game.InstanceSettings settings = gameInstance.settings();

/*        // for debug
        Os.setenv("MESA_DEBUG", "1", false);
        Os.setenv("MESA_LOG_LEVEL", "debug", false);
        Os.setenv("ZINK_DEBUG", "validation", false);
        Os.setenv("mesa_glthread", "false", false);
        Os.setenv("GALLIUM_THREAD", "0", false);
        Os.setenv("VK_LOADER_DEBUG", "all", false);
        Os.setenv("VK_DEBUG", "all", false);
        Os.setenv("GALLIUM_DEBUG", "all", false);
        Os.setenv("VK_LOADER_LAYERS_ENABLE", "VK_LAYER_KHRONOS_validation", false);
        Os.setenv("BOX64_LOG", "3", false);
        Os.setenv("BOX64_DYNAREC", "0", false);*/

        //Os.setenv("LIBGL_NOERROR", "1", false);
        //Os.setenv("LIBGL_LOGSHADERERROR", "1", false);
        //Os.setenv("ZINK_DEBUG", "spirv", false);

        Os.setenv("LIBGL_MIPMAP", "1", false);

        boolean verboseNativeLogs = BuildConfig.DEBUG
                || settings.isDebug();
        Os.setenv("BOX64_LOG", verboseNativeLogs ? "1" : "0", false);
        Os.setenv("BOX64_SHOWBT", verboseNativeLogs ? "1" : "0", false);
        // Per-symbol detail of the macOS loader ([macho] bridge, [jni-bind], LDAPR offsets): dozens
        // of lines per launch that only matter when the loader itself is being debugged.
        Os.setenv("ZOMDROID_NATIVE_VERBOSE", verboseNativeLogs ? "1" : "0", false);
        Os.setenv("BOX64_LD_LIBRARY_PATH", gameInstance.getLdLibraryPathForEmulation(), false);

        // Emulate x86's Total Store Order for the emulated libraries. box64 defaults to no
        // barriers at all, which is fine for single-threaded code but breaks x86 code written
        // against TSO once ARM's weaker model is allowed to reorder. box64 itself force-enables
        // this combination for the multithreaded libraries it knows about (libjvm, libtbb,
        // MonoBleedingEdge - see box64 librarian/library.c); PZ's Lighting is multithreaded too
        // (it runs its own thread, "LightingFPS set to 15") but is not on that list.
        // Level 3 = barrier on every third guest store, the strongest setting; BIGBLOCK=0 stops
        // block merging from moving stores across the barriers.
        Os.setenv("BOX64_DYNAREC_STRONGMEM", "3", false);
        Os.setenv("BOX64_DYNAREC_BIGBLOCK", "0", false);

        Os.setenv("GALLIUM_DRIVER", "zink", false);

        Os.setenv("ZOMDROID_CACHE_DIR", AppStorage.requireSingleton().getCachePath(), false);
        Os.setenv("ZOMDROID_RENDERER", settings.getRenderer().name(), false);
        switch (settings.getRenderer()) {
            case ZINK_ZFA:
            case ZINK_OSMESA:
                String vulkanDriverName = settings.getVulkanDriver().libName;
                if (vulkanDriverName != null) {
                    Os.setenv("ZOMDROID_VULKAN_DRIVER_NAME", vulkanDriverName, false);
                }
                // Our Mesa carries an ETC2 encoder for large RGBA8 uploads (zomdroid_texetc2.c).
                // On unless the instance's "Texture compression" switch is off; a value typed
                // into the env vars still wins, they are applied after this block. Its disk
                // cache is the same store NG_GL4ES uses (same encoder, same hash), which is why
                // the "Clear" button in Settings covers both. The path is passed explicitly: the
                // library's built-in default is /data/data/..., and on some devices the app's
                // real data directory is /data/user/0/... instead.
                Os.setenv("ZOMDROID_ZINK_ETC2", settings.isTextureCompression() ? "1" : "0", false);
                Os.setenv("ZOMDROID_ETC2_CACHE_DIR",
                        AppStorage.requireSingleton().getHomePath() + "/" + C.NGG_ETC2_CACHE_DIR,
                        false);
                break;
            case NG_GL4ES: {
                //Os.setenv("LIBGL_ES", "3", true);
                //Os.setenv("LIBGL_GL", "21", true); // если нужен OpenGL 2.1 для движка
                //Os.setenv("LIBGL_NOBANNER", "0", true);
                //Os.setenv("LIBGL_SILENTSTUB", "0", true); // если хотите убрать шум
                //Os.setenv("LIBGL_FB", "2", true);
                //Os.setenv("LIBGL_FBONOALPHA", "1", true);
                //Os.setenv("LIBGL_SIMPLE_SHADERCONV", "1", true);
                //Os.setenv("LIBGL_DBGSHADERCONV", "15", true);
                // Force SPIRV-Cross path instead of old ConvertShader
                // Without this, esversion stays 200 and shaders go through
                // the old converter that doesn't understand modern GLSL
                //Os.setenv("LIBGL_VGPU_FORCE", "1", true);
                //Os.setenv("LIBGL_VGPU_PRECISION", "1", true);
                // The DECISIVE knob is the real EGL context version, not the GL version the game
                // sees. On a true ES3 context Mali runs NG's internal ES3 paths, which
                // deterministically kill box64/physics at Bullet.init; a 2.1 context yields Mali's
                // ES2 profile and clean ES2 paths (proven playable). So: Qualcomm/Adreno -> ES3.2
                // context, everyone else -> ES2.1 context. We own the context; the lib (RC13+) owns
                // the badge and picks it from the actual context — do NOT set LIBGL_GL here, it
                // would override the lib's decision. override=false keeps manual env overrides.
                // Memory saver (Settings → Advanced): live-texture budget in MB. Past this
                // threshold NG_GL4ES loads new large textures at half resolution — caps runaway
                // texture memory at the cost of tile detail. Unset = 0 = the mechanism sleeps.
                // override=false so a manual LIBGL_TEXBUDGET in the env-vars field still wins.
                if (settings.isMemorySaver()) {
                    Os.setenv("LIBGL_TEXBUDGET", "800", false);
                }
                boolean isQualcomm = isQualcommGpu();
                Os.setenv("ZOMDROID_GLES_MAJOR", isQualcomm ? "3" : "2", false);
                Os.setenv("ZOMDROID_GLES_MINOR", isQualcomm ? "2" : "1", false);
                Os.setenv("LIBGL_ES", "2", false);
                Os.setenv("LIBGL_MIPMAP", "1", false);
                Os.setenv("LIBGL_LOGSHADERERROR", "1", false);
                Os.setenv("LIBGL_VGPU_DUMP", "1", false);
                // NG's ETC2 texture compression (LIBGL_ETC2, off in the library itself): same
                // switch, same disk cache as the ZINK path above. Note it takes every texture
                // of 512x512 and up at full resolution BEFORE the shrink logic runs, so with it
                // on LIBGL_SHRINK=7 has nothing left to halve - that is why the tile grid is
                // gone, not because the two combine. Needs a GLES 3 context (esversion >= 300).
                Os.setenv("LIBGL_ETC2", settings.isTextureCompression() ? "1" : "0", false);
                // DEBUG: red-clear bisection — disabled now that swap/context are
                // confirmed alive; uncomment to mask frames again if needed.
                //Os.setenv("ZOMDROID_DEBUG_RED_CLEAR", "1", false);
                break;
            }
            case MOBILEGLUES_EXPERIMENTAL: {
                // MobileGlues runs on a real GLES 3.x context; 3.2 is what the fork was tested on.
                Os.setenv("ZOMDROID_GLES_MAJOR", "3", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "2", false);
                // Its config.json, latest.log and GLSL cache live here. Without MG_DIR_PATH the
                // library falls back to /sdcard/MG, cannot create it and silently drops its log.
                File mgDir = new File(AppStorage.requireSingleton().getHomePath(), "mobileglues");
                if (!mgDir.isDirectory() && !mgDir.mkdirs()) {
                    Log.w("Zomdroid", "Failed to create MobileGlues directory " + mgDir);
                }
                Os.setenv("MG_DIR_PATH", mgDir.getAbsolutePath(), false);
                break;
            }
            default: {
                Os.setenv("ZOMDROID_GLES_MAJOR", "2", false);
                Os.setenv("ZOMDROID_GLES_MINOR", "1", false);
                break;
            }
        }

        Os.setenv("ZOMDROID_AUDIO_API", LauncherPreferences.requireSingleton().getAudioAPI().name(), false);

        if (BuildConfig.DEBUG) {
            //for debugging GL calls, only supported on GL ES 3.2+ with GL_KHR_debug extension present
            Os.setenv("LIBGL_STACKTRACE","1", false);
            Os.setenv("LIBGL_LOGSHADERERROR","1", false);
        }
        initZomdroidWindow();
        InputNativeInterface.sendJoystickConnected();

        // JVM args [variables] from user settings
        ArrayList<String> jvmArgs = gameInstance.getJvmArgsAsList();
        String rawArgs = settings.getJvmArgs();

        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            String[] splitArgs = rawArgs.trim().split("\\s+");
            for (String arg : splitArgs) {
                jvmArgs.add(arg);
            }
        }

        // Environment variables from user settings
        String rawEnvVars = settings.getEnvVars();
        if (rawEnvVars != null && !rawEnvVars.trim().isEmpty()) {
            for (String token : rawEnvVars.trim().split("\\s+")) {
                String[] parts = token.split("=", 2);
                if (parts.length == 2) {
                    Os.setenv(parts[0].trim(), parts[1].trim(), true);
                }
            }
        }

        // Debug builds always carry the emulated-JNI statistics ([JNISTAT] in native.log); a user
        // value of 0 still turns them off.
        if (BuildConfig.DEBUG && Os.getenv("ZOMDROID_JNI_STATS") == null) {
            Os.setenv("ZOMDROID_JNI_STATS", "1", true);
        }

        jvmArgs.add("-Dorg.lwjgl.opengl.libname=" + settings.getRenderer().libName);
        // Sound: the OpenAL Soft built for Android (app/src/main/jniLibs, see LICENSE-openal-soft)
        // that LWJGL's OpenAL binding opens. The game falls back to silence if it cannot.
        jvmArgs.add("-Dorg.lwjgl.openal.libname=" + AppStorage.requireSingleton().getLibraryPath() + "/libopenal.so");
        jvmArgs.add("-Dzomdroid.renderer=" + settings.getRenderer().name());
        if (BuildConfig.DEBUG) {
            jvmArgs.add("-Dorg.lwjgl.util.Debug=true"); //print LWJGL library errors
            //jvmArgs.add("-Dorg.lwjgl.util.DebugLoader=true");
            jvmArgs.add("-XX:+PrintFlagsFinal"); // for debugging
        }

        jvmArgs.add("-XX:ErrorFile=/dev/stdout"); // print jvm crash report to stdout for now
        // One line per garbage collection into native.log (stderr is mirrored there): heap before
        // -> after (committed) and the pause. The heap after a collection is what a world really
        // needs, which decides whether phones short on memory can get a lower -Xmx (2026-09-16).
        jvmArgs.add("-Xlog:gc:stderr");


        ArrayList<String> args = gameInstance.getArgsAsList();
        Log.i("Zomdroid", "JVM ARGS: " + jvmArgs);
        Log.i("Zomdroid", "GAME ARGS: " + args);

        // Java 21 is the only runtime the launcher ships; Deephall is built for Java 17.
        String javaHomePath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE_21;
        String ldLibraryPath = AppStorage.requireSingleton().getLibraryPath() + ":/system/lib64:"
                + javaHomePath + "/lib:" + javaHomePath + "/lib/server:" + gameInstance.getJavaLibraryPath();
        //Log.d("zomdroid-main", ldLibraryPath);
        GameLauncher.startGame(gameInstance.getGamePath(), ldLibraryPath, jvmArgs.toArray(new String[0]),
                gameInstance.getMainClassName(), args.toArray(new String[0]));
    }

    // Positive-ID Qualcomm/Adreno only (they tolerate the ES3 EGL context). Everything else —
    // MediaTek/Mali, and any unknown, to stay safe — returns false so NG gets the ES2 context.
    // Delegates to the one shared detector: this used to be a private copy with the same
    // unguarded Build.SOC_* array that crashed Android 11 (those fields are API 31+, and
    // NoSuchFieldError is not an Exception) — the guard must live in exactly one place.
    private static boolean isQualcommGpu() {
        return com.zomdroid.game.SuggestedPreset.QUALCOMM.equals(
                com.zomdroid.game.SuggestedPreset.detectGpuVendor());
    }

    public static native int initZomdroidWindow();
    public static native void destroyZomdroidWindow();
    public static native int setSurface(Surface surface, int width, int height);
    public static native void destroySurface();
    /** Frames the game has presented so far (glfwSwapBuffers), for the in-game FPS display. */
    public static native long getPresentedFrameCount();
    static native void startGame(String gameDirPath, String libraryDirPath, String[] jvmArgs, String mainClassName, String[] args);
}
