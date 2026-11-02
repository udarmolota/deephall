package com.zomdroid;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import androidx.annotation.NonNull;

import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.game.InstallationPreset;
import com.zomdroid.game.InstanceSettings;
import com.zomdroid.game.PresetManager;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The game the standalone Deephall app carries inside its own APK, and the one instance it plays.
 *
 * <p>The debug build packs deephall-game/ (mountaincore.jar, lwjgl336/, mods/) into
 * {@link #ASSET_PAYLOAD} at build time, with a version stamp beside it (see packDeephallGame in
 * app/build.gradle.kts). {@link #prepare} turns that into a ready instance: it creates the
 * "Deephall" instance the first time, replaces its game files whenever the bundled stamp differs
 * from the one recorded in the game dir, and gives it the on-screen controls if it has none.
 *
 * <p>What it never touches: the game's own user files in instances/Deephall/Mountaincore (saves,
 * the game's preferences) and the game dir's controls/ once a layout is there. The game rebuilds
 * its assets/ folder from mods/ by checksum on every start, so that folder is left alone too.
 */
public final class BundledGame {
    public static final String INSTANCE_NAME = "Deephall";
    private static final String PRESET_NAME = "Deephall";

    public static final String ASSET_PAYLOAD = "game/deephall-game.zip";
    private static final String ASSET_VERSION = "game/deephall-game.version";
    private static final String ASSET_CONTROLS_DIR = "deephall_controls";

    // Lives in the game dir, beside the files it describes: deleting the instance takes it along,
    // so a re-created instance can never inherit a stamp for files it does not have.
    private static final String STAMP_FILE_NAME = ".bundled-game-version";

    // Everything the payload owns inside the game dir. Folders are deleted before extraction so a
    // file dropped from a newer payload does not linger from the older one.
    private static final String GAME_JAR = "mountaincore.jar";
    private static final String[] PAYLOAD_DIRS = {"lwjgl336", "mods"};

    private BundledGame() {}

    /**
     * Makes the bundled instance ready to launch. Runs on a worker thread; progress goes to
     * listener. Safe to interrupt at any point: the stamp is removed before game files are touched
     * and written only after they are all in place, so the next start redoes an unfinished update.
     */
    static void prepare(@NonNull Context context, @NonNull TaskProgressListener listener) throws IOException {
        GameInstanceManager manager = GameInstanceManager.requireSingleton();
        GameInstance instance = manager.getInstanceByName(INSTANCE_NAME);
        boolean created = false;
        if (instance == null) {
            InstallationPreset preset = PresetManager.findByName(PRESET_NAME);
            if (preset == null) throw new IOException("Installation preset " + PRESET_NAME + " is missing");
            instance = new GameInstance(INSTANCE_NAME, preset);
            manager.registerInstance(instance);
            applyDefaultSettings(instance.settings());
            created = true;
        }

        File gameDir = new File(instance.getGamePath());
        File stampFile = new File(gameDir, STAMP_FILE_NAME);
        String bundledStamp = readAssetText(context, ASSET_VERSION).trim();
        String installedStamp = stampFile.isFile()
                ? new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8).trim()
                : null;

        if (created || !bundledStamp.equals(installedStamp) || !instance.hasGameFiles()) {
            listener.onProgressUpdate(context.getString(R.string.deephall_installing_game), -1, 0);
            //noinspection ResultOfMethodCallIgnored
            stampFile.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(gameDir, GAME_JAR).delete();
            for (String dir : PAYLOAD_DIRS) {
                File payloadDir = new File(gameDir, dir);
                if (payloadDir.exists()) FileUtils.deleteDirectory(payloadDir);
            }
            try (InputStream in = context.getAssets().open(ASSET_PAYLOAD)) {
                FileUtils.extractZipToDisk(in, gameDir.getPath(), listener, assetLength(context, ASSET_PAYLOAD));
            }
            if (!instance.hasGameFiles())
                throw new IOException("The bundled game has no " + GAME_JAR);
            try (OutputStream out = new FileOutputStream(stampFile, false)) {
                out.write(bundledStamp.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (!instance.isInstallationFinished()) manager.markInstallationFinished(instance);

        installDefaultControls(context, gameDir);
    }

    /** The settings Deephall is known to run with on the developer's phone. */
    private static void applyDefaultSettings(InstanceSettings settings) {
        settings.setRenderer(LauncherPreferences.Renderer.GL4ES);
        settings.setEnvVars("LIBGL_GL=30");
        settings.setRenderScale(0.6f);
        settings.setVibrateOnTouch(true);
        settings.setHudMode(InstanceSettings.HUD_OFF);
    }

    /**
     * Copies the bundled Deephall layout and its icons into game/controls when the instance has no
     * layout yet. A layout that is already there is the player's, edited or not, and stays.
     */
    private static void installDefaultControls(Context context, File gameDir) throws IOException {
        File controlsDir = new File(gameDir, "controls");
        File controlsJson = new File(controlsDir, "controls.json");
        if (controlsJson.isFile()) return;
        if (!controlsDir.isDirectory() && !controlsDir.mkdirs())
            throw new IOException("Failed to create directory " + controlsDir);
        FileUtils.copyAssetsToDisk(context, ASSET_CONTROLS_DIR + "/icons",
                new File(controlsDir, "icons").getPath());
        // The layout goes last: it is what marks the controls as installed.
        FileUtils.copyAssetsToDisk(context, ASSET_CONTROLS_DIR + "/controls.json", controlsJson.getPath());
    }

    private static String readAssetText(Context context, String assetPath) throws IOException {
        try (InputStream in = context.getAssets().open(assetPath)) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        }
    }

    // The payload is stored uncompressed in the APK (noCompress in app/build.gradle.kts), which is
    // what lets openFd() report its size; -1 just turns the progress bar indeterminate.
    private static long assetLength(Context context, String assetPath) {
        try (AssetFileDescriptor fd = context.getAssets().openFd(assetPath)) {
            return fd.getLength();
        } catch (IOException e) {
            return -1;
        }
    }
}
