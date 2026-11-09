package com.zomdroid.game;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Texture compression for Deephall, as the instance settings offer it: none (full size), low (the half-size
 * copies the phone build carries for the biggest textures, the game's ScaledTextures) and ultra low (those
 * copies, halved again by GL4ES with LIBGL_SHRINK=7). The game reads the set from {@code -Ddeephall.textures},
 * which {@link com.zomdroid.GameLauncher} sets from here. Until the player picks one, the phone's RAM decides
 * between none and low.
 *
 * <p>Reduced copies take a quarter of the memory and load faster: the game reads the small file instead of
 * decoding the full picture, so there is no peak of full-size pictures while the game loads, which is what
 * killed it on a 4 GB phone. LIBGL_SHRINK cannot do that: GL4ES shrinks a texture only when it is handed
 * over to the GPU, after the game has decoded it at full size. It still works on top of the reduced set,
 * halving those again, as a last resort.
 */
public final class GameTextures {
    public static final String AUTO = "auto";
    public static final String FULL = "full";
    public static final String REDUCED = "reduced";
    public static final String ULTRA = "ultra";

    /** The GL4ES shrink mode ultra low adds: halves what is over 512, skips empty textures. */
    public static final String ULTRA_LIBGL_SHRINK = "7";

    /** Phones with less RAM than this get low compression until the player picks. */
    private static final long FULL_TEXTURES_MIN_RAM_GB = 6;

    private GameTextures() {}

    /** The JVM argument for this instance. */
    public static String jvmArg(Context context, InstanceSettings settings) {
        return "-Ddeephall.textures=" + (useReduced(context, settings) ? REDUCED : FULL);
    }

    public static boolean useReduced(Context context, InstanceSettings settings) {
        String choice = effectiveChoice(context, settings);
        return REDUCED.equals(choice) || ULTRA.equals(choice);
    }

    public static boolean useUltra(Context context, InstanceSettings settings) {
        return ULTRA.equals(effectiveChoice(context, settings));
    }

    /** FULL, REDUCED or ULTRA: the player's pick, or what the phone's RAM suggests before there is one. */
    public static String effectiveChoice(Context context, InstanceSettings settings) {
        String choice = settings.getGameTextures();
        if (FULL.equals(choice) || REDUCED.equals(choice) || ULTRA.equals(choice)) return choice;
        return reducedByDefault(context) ? REDUCED : FULL;
    }

    /** What Auto picks on this phone. */
    public static boolean reducedByDefault(Context context) {
        return totalRamGb(context) < FULL_TEXTURES_MIN_RAM_GB;
    }

    /**
     * Installed RAM, rounded up: MemTotal is what the kernel manages, so a 6 GB phone reports about 5.5 and
     * rounding up makes the comparison mean the number on the box. An unknown amount counts as plenty, so
     * nobody gets the softer picture by mistake.
     */
    public static long totalRamGb(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return Long.MAX_VALUE;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        if (info.totalMem <= 0) return Long.MAX_VALUE;
        return Math.round(Math.ceil(info.totalMem / (1024.0 * 1024.0 * 1024.0)));
    }
}
