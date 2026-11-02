package com.zomdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.game.InstallationPreset;
import com.zomdroid.game.PresetManager;
import com.zomdroid.game.SuggestedPreset;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class InstallerService extends Service implements TaskProgressListener {
    private static final String LOG_TAG = InstallerService.class.getName();
    private static final String CHANNEL_ID = "com.zomdroid.InstallerService.NOTIFICATION_CHANNEL";
    private static final int NOTIFICATION_ID = 1;

    // Intent action broadcast when service starts
    public static final String ACTION_STARTED = "com.zomdroid.InstallerService.ACTION_STARTED";

    // Intent extras
    public static final String EXTRA_COMMAND = "com.zomdroid.InstallerService.EXTRA_COMMAND";
    public static final String EXTRA_GAME_INSTANCE_NAME = "com.zomdroid.InstallerService.EXTRA_GAME_INSTANCE_NAME";
    public static final String EXTRA_ARCHIVE_URI = "com.zomdroid.InstallerService.EXTRA_ARCHIVE_URI";
    public static final String EXTRA_CONTROLS_URI = "com.zomdroid.InstallerService.EXTRA_CONTROLS_URI";
    public static final String EXTRA_OUTPUT_URI = "com.zomdroid.InstallerService.EXTRA_OUTPUT_URI";
    public static final String EXTRA_DRIVER_URI = "com.zomdroid.InstallerService.EXTRA_DRIVER_URI";
    public static final String EXTRA_INSTALL_PRESET_NAME = "com.zomdroid.InstallerService.EXTRA_INSTALL_PRESET_NAME";
    public static final String EXTRA_GPU_VENDOR = "com.zomdroid.InstallerService.EXTRA_GPU_VENDOR";
    public static final String EXTRA_RUN_TOKEN = "com.zomdroid.InstallerService.EXTRA_RUN_TOKEN";

    private final IBinder binder = new LocalBinder();
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private NotificationManagerCompat notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastProgressUpdateMs;
    private final MutableLiveData<TaskState> taskState = new MutableLiveData<>();
    private Task currentTask;
    private String currentInstallPresetName;
    private String currentGpuVendor;
    // Name of the instance the current CREATE_GAME_INSTANCE task is building. The post-install
    // setup dialog applies a preset, and presets are per-instance now, so it has to know which.
    private String currentInstanceName;
    // True between the start of a user-initiated task and its finish/error state. Guards against
    // the launch-time dependency re-check taking the service over mid-install; see onStartCommand.
    private volatile boolean userTaskRunning;
    private static volatile boolean taskRunning;
    public static boolean isTaskRunning() { return taskRunning; }
    private volatile int lastStartId;
    // Who asked for the current PREPARE_BUNDLED_GAME run (see DeephallStartActivity). The task
    // state outlives a task, so a screen binding in sees whatever the last task left behind; the
    // token is how it tells its own run from an earlier one.
    private volatile long currentRunToken;

    /**
     * Stops the service unless it has been started again since its last task began. The screens call
     * this when they see a task finish, instead of Context.stopService(): a screen coming back to the
     * front can still hold an observer of an earlier task, and its stale "finished" used to stop a
     * service that had been started again a moment before - LauncherFragment re-runs the dependency
     * check every time it is shown - before that start reached startForeground(). Android then kills
     * the whole app with ForegroundServiceDidNotStartInTimeException (S25, 2026-09-17, after two
     * exports in a row and a return to the home screen). stopSelfResult() compares the start ID with
     * the newest one the system has handed out, so a pending start keeps the service alive.
     */
    public void stopUnlessRestarted() {
        stopSelfResult(lastStartId);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GameInstallerServiceChannel", NotificationManager.IMPORTANCE_LOW);

        notificationManager = NotificationManagerCompat.from(this);
        notificationManager.createNotificationChannel(channel);

        Task task = Task.values()[intent.getIntExtra(EXTRA_COMMAND, 0)];
        // The dependency re-check runs on every launcher start, including the moment we navigate
        // back from instance creation — right while CREATE_GAME_INSTANCE is still working. Letting
        // it through would reassign currentTask and make the launcher miss the create-instance
        // finish (that is how the post-install renderer/GPU dialog silently stopped appearing).
        // The check is cheap and repeats next launch, so skipping this one occasion is free.
        if (task == Task.INSTALL_DEPENDENCIES && userTaskRunning) {
            // The skip must still keep the promise startForegroundService() made, or the system
            // kills the whole process ten seconds later (ForegroundServiceDidNotStartInTime) -
            // mid-install, taking the running task with it. Shipped broken since 1.4.7v2; the
            // window is narrow (the fragment has to be recreated - rotation, minimize, a trip to
            // Settings - while a task runs), which is why it took three weeks to be reported.
            // The running task's OWN notification is re-posted so nothing visibly changes; the
            // fallback exists only for the freak case where none was built yet. No stopSelf: the
            // service is mid-task and must keep living.
            startForeground(NOTIFICATION_ID, notificationBuilder != null
                    ? notificationBuilder.build()
                    : buildNotification(getString(R.string.dialog_title_installing_dependencies)));
            Log.i(LOG_TAG, "Dependency re-check skipped: " + currentTask + " is still running");
            return START_NOT_STICKY;
        }
        // A second start screen while the game is already being prepared (the first one was
        // recreated, or the notification opened a new one): hand the running task over to it
        // instead of queueing the same work again behind itself.
        if (task == Task.PREPARE_BUNDLED_GAME && currentTask == Task.PREPARE_BUNDLED_GAME
                && userTaskRunning) {
            startForeground(NOTIFICATION_ID, notificationBuilder != null
                    ? notificationBuilder.build()
                    : buildNotification(getString(R.string.deephall_preparing), launchActivityIntent()));
            currentRunToken = intent.getLongExtra(EXTRA_RUN_TOKEN, 0);
            return START_NOT_STICKY;
        }

        currentTask = task;
        taskRunning = true;
        userTaskRunning = task != Task.INSTALL_DEPENDENCIES;
        // Only overwrite when the intent actually carries them. Every task shares this service,
        // and returning to the launcher fires updateDependencies() — an INSTALL_DEPENDENCIES
        // intent without these extras used to null them out mid-install, so by the time
        // CREATE_GAME_INSTANCE finished the post-install setup dialog had no preset/GPU left to
        // show (and currentTask no longer said CREATE_GAME_INSTANCE either).
        if (intent.hasExtra(EXTRA_INSTALL_PRESET_NAME)) {
            currentInstallPresetName = intent.getStringExtra(EXTRA_INSTALL_PRESET_NAME);
        }
        if (intent.hasExtra(EXTRA_GPU_VENDOR)) {
            currentGpuVendor = intent.getStringExtra(EXTRA_GPU_VENDOR);
        }
        if (intent.hasExtra(EXTRA_GAME_INSTANCE_NAME)) {
            currentInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        }
        currentRunToken = intent.getLongExtra(EXTRA_RUN_TOKEN, 0);

        Intent serviceStartedBroadcast = new Intent(ACTION_STARTED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(serviceStartedBroadcast);

        switch (task) {
            case CREATE_GAME_INSTANCE:
                doCreateGameInstance(intent);
                break;
            case DELETE_GAME_INSTANCE:
                doDeleteGameInstance(intent);
                break;
            case INSTALL_DEPENDENCIES:
                doInstallDependencies(intent);
                break;
            case INSTALL_CONTROLS_TO_INSTANCE:
                doInstallControlsToInstance(intent);
                break;
            case EXPORT_CONTROLS_FROM_INSTANCE:
                doExportControlsFromInstance(intent);
                break;
            case IMPORT_CUSTOM_DRIVER:
                doImportCustomDriver(intent);
                break;
            case EXPORT_CUSTOM_DRIVER:
                doExportCustomDriver(intent);
                break;
            case EXPORT_LOG:
                doExportLog(intent);
                break;
            case PREPARE_BUNDLED_GAME:
                doPrepareBundledGame();
                break;
        }

        return START_NOT_STICKY;
    }

    // -------------------- CREATE GAME INSTANCE --------------------

    private void doCreateGameInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_creating_instance);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_create_instance),
                    "Game instance name intent extra is missing");
            return;
        }
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_create_instance),
                    "Game instance with name " + gameInstanceName + " not found");
            return;
        }

        Uri gameFilesArchiveUri = intent.getParcelableExtra(EXTRA_ARCHIVE_URI);
        if (gameFilesArchiveUri == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_create_instance),
                    "Game files archive URI intent extra is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                // The game arrives as the folder it runs from: nothing to lift out of wrapper
                // folders, no native layout to normalise and no classes to patch.
                installGameFromZip(gameInstance, gameFilesArchiveUri);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_create_instance), e.toString());
                return;
            }

            GameInstanceManager.requireSingleton().markInstallationFinished(gameInstance);
            finish(getString(R.string.dialog_title_instance_created), null);
        });
    }

    // -------------------- DELETE GAME INSTANCE --------------------

    private void doDeleteGameInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_deleting_game_instance);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String gameInstanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_delete_instance),
                    "Game instance name intent extra is missing");
            return;
        }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null) {
            finishWithError(getString(R.string.dialog_title_failed_to_delete_instance),
                    "Game instance with name " + gameInstanceName + " not found");
            return;
        }

        executorService.submit(() -> {
            try {
                FileUtils.deleteDirectory(new File(gameInstance.getHomePath()));
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_delete_instance), e.toString());
                return;
            }

            GameInstanceManager.requireSingleton().unregisterInstance(gameInstance);
            // Drop this instance's stored launch settings too. They are keyed by name, and names
            // are reusable, so leaving them behind would silently hand a dead instance's renderer
            // and JVM arguments to whatever is created next under the same name.
            com.zomdroid.game.InstanceSettings.forget(gameInstanceName);
            finish(getString(R.string.dialog_title_instance_deleted), null);
        });
    }

    // -------------------- INSTALL DEPENDENCIES --------------------

    // Dependency folders of bundles the launcher no longer ships, relative to the app's home.
    private static final String[] RETIRED_DEPENDENCY_DIRS = {
            C.deps.ROOT + "/jre25", C.deps.ROOT + "/jars"
    };

    // Called on every app launch (see LauncherFragment#updateDependencies), not just the first
    // one, so an APK update whose bundled libs.tar.xz/jre changed can pick that up without
    // a full app reinstall. To keep that cheap and silent on the common case (nothing changed),
    // we CRC-check all bundles up front and only show the install notification/dialog and touch
    // disk for the ones that actually differ.
    private void doInstallDependencies(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_installing_dependencies);

        // startForeground() must be called promptly regardless of whether anything turns out to
        // have changed — Android requires it soon after startForegroundService().
        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));

        executorService.submit(() -> {
            boolean anyChanged;
            try {
                anyChanged = syncDependencies(taskTitle);
            } catch (IOException e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_dependencies), e.toString());
                return;
            }
            // Nothing changed: never posted an "in progress" state, so no dialog ever showed.
            finish(anyChanged ? getString(R.string.dialog_title_dependencies_installed) : null, null);
        });
    }

    /**
     * Brings jre21 and libs on disk in line with the bundles in this APK. Returns whether anything
     * had to be extracted. An "in progress" state titled taskTitle is posted only then, so the
     * common case (nothing changed) stays silent. Runs on the executor.
     */
    private boolean syncDependencies(String taskTitle) throws IOException {
        SharedPreferences prefs = getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
        Gson gson = new Gson();

        String bundlesJson = prefs.getString(C.shprefs.keys.INSTALLED_BUNDLES, "[]");

        Type mapType = new TypeToken<HashMap<String, Long>>() {}.getType();
        HashMap<String, Long> oldBundlesHashesMap = gson.fromJson(bundlesJson, mapType);
        HashMap<String, Long> newBundlesHashesMap = new HashMap<>();

        // --- Pass 1: CRC32 every bundle against its last-installed hash. Quiet and cheap —
        // reads the (already-compressed) asset bytes straight out of the APK, no extraction.
        Long jre21HashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_JRE21);
        newBundlesHashesMap.put(C.assets.BUNDLES_JRE21, jre21HashNew);
        boolean jre21Changed = !jre21HashNew.equals(oldBundlesHashesMap.get(C.assets.BUNDLES_JRE21));

        Long libsHashNew = FileUtils.generateCRC32ForAsset(this, C.assets.BUNDLES_LIBS);
        newBundlesHashesMap.put(C.assets.BUNDLES_LIBS, libsHashNew);
        boolean libsChanged = !libsHashNew.equals(oldBundlesHashesMap.get(C.assets.BUNDLES_LIBS));

        // Java 25 and the jars bundle (Project Zomboid's agent and SQLite driver) are no longer
        // shipped. An install made by a launcher that still carried them keeps their folders on
        // disk until this removes them; a missing folder makes this a pair of stat calls.
        for (String retired : RETIRED_DEPENDENCY_DIRS) {
            File dir = new File(AppStorage.requireSingleton().getHomePath(), retired);
            if (dir.exists()) FileUtils.deleteDirectory(dir);
        }

        boolean anyChanged = jre21Changed || libsChanged;
        if (!anyChanged) {
            // Nothing to extract — but still record that the dependencies ARE installed:
            // every bundle just passed a CRC32 check against the APK's own copy, which is a
            // stronger statement than the flag itself makes.
            //
            // Skipping this write used to strand the launcher permanently. ZomdroidApplication
            // clears ARE_DEPENDENCIES_INSTALLED on any versionCode change, and before this
            // check existed a cleared flag always meant a full re-extract that set it again.
            // Now a release that touches only code (not the asset bundles) leaves every bundle
            // identical, so we land here, return without the flag, and LauncherFragment refuses
            // to start the game - on this launch and on every launch after it, with no manual
            // way out. Hit on 2026-08-01 going 1.4.7 -> 1.4.8; it would have hit every user of
            // any code-only release.
            prefs.edit().putBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, true).apply();
            return false;
        }

        // --- Pass 2: only now announce the install, and only extract what changed ---
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        if (jre21Changed) {
            String jre21Path = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.JRE_21;
            File jre21Dir = new File(jre21Path);
            if (jre21Dir.exists()) FileUtils.deleteDirectory(jre21Dir);

            try (InputStream jreBundleInStream = getAssets().open(C.assets.BUNDLES_JRE21)) {
                announceExtraction();
                FileUtils.extractTarXzToDisk(jreBundleInStream, jre21Path, this, 0);
            }
        }

        if (libsChanged) {
            String libsPath = AppStorage.requireSingleton().getHomePath() + "/" + C.deps.LIBS;
            File libsDir = new File(libsPath);
            if (libsDir.exists()) FileUtils.deleteDirectory(libsDir);

            try (InputStream libsBundleInStream = getAssets().open(C.assets.BUNDLES_LIBS)) {
                announceExtraction();
                FileUtils.extractTarXzToDisk(libsBundleInStream, libsPath, this, 0);
            }
        }

        bundlesJson = gson.toJson(newBundlesHashesMap);
        prefs.edit()
                .putString(C.shprefs.keys.INSTALLED_BUNDLES, bundlesJson)
                .putBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, true)
                .apply();
        return true;
    }

    // -------------------- PREPARE BUNDLED GAME --------------------

    // The standalone Deephall app's whole start-up in one task, so its start screen has a single
    // run to follow: dependencies first, then the instance and its game files (BundledGame).
    private void doPrepareBundledGame() {
        String taskTitle = getString(R.string.deephall_preparing);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle, launchActivityIntent()));
        // setValue, not postValue: onStartCommand runs on the main thread, and replacing the
        // previous task's final state right here means no observer can ever see that state
        // together with this run's token.
        this.taskState.setValue(new TaskState(taskTitle, null, -1, 0, false, false));

        executorService.submit(() -> {
            try {
                syncDependencies(taskTitle);
                BundledGame.prepare(this, this);
            } catch (Exception e) {
                finishWithError(getString(R.string.deephall_prepare_failed), e.toString());
                return;
            }
            finish(null, null);
        });
    }

    // -------------------- INSTALL CONTROLS TO INSTANCE --------------------

    private void doInstallControlsToInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_installing_controls);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (instanceName == null) {
            finishWithError(taskTitle, "Game instance name is missing");
            return;
        }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (gameInstance == null) {
            finishWithError(taskTitle, "Game instance not found: " + instanceName);
            return;
        }

        Uri controlsArchiveUri = intent.getParcelableExtra(EXTRA_CONTROLS_URI);
        if (controlsArchiveUri == null) {
            finishWithError(taskTitle, "Controls archive URI is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                String controlsDirPath = gameInstance.getGamePath() + "/controls";
                File controlsDir = new File(controlsDirPath);
                if (!controlsDir.exists()) controlsDir.mkdirs();

                File outFile = new File(controlsDir, "controls.json");
                File iconsDir = new File(controlsDir, "icons");
                boolean found = false;

                try (InputStream is = getContentResolver().openInputStream(controlsArchiveUri)) {
                    if (is == null) throw new IllegalStateException("openInputStream returned null");
                    try (ZipInputStream zis = new ZipInputStream(is)) {
                        ZipEntry e;
                        byte[] buf = new byte[64 * 1024];

                        // Process every entry (do NOT stop at controls.json): we also extract the
                        // icons/ folder so user-supplied button/radial images travel with the layout.
                        while ((e = zis.getNextEntry()) != null) {
                            if (e.isDirectory()) continue;

                            String name = e.getName();
                            if (name == null) continue;
                            String lower = name.toLowerCase();

                            // Accept both "controls.json" and "something/controls.json"
                            if (lower.endsWith("controls.json")) {
                                try (OutputStream os = new FileOutputStream(outFile, false)) {
                                    int r;
                                    while ((r = zis.read(buf)) != -1) {
                                        os.write(buf, 0, r);
                                    }
                                    os.flush();
                                }
                                found = true;
                            } else if (lower.contains("icons/")) {
                                // Custom button/radial image. Use only the file name (no directory
                                // components) to guard against zip-slip path traversal.
                                String norm = name.replace('\\', '/');
                                String base = norm.substring(norm.lastIndexOf('/') + 1);
                                if (!base.isEmpty() && !base.contains("..")) {
                                    if (!iconsDir.exists()) iconsDir.mkdirs();
                                    File iconOut = new File(iconsDir, base);
                                    try (OutputStream os = new FileOutputStream(iconOut, false)) {
                                        int r;
                                        while ((r = zis.read(buf)) != -1) {
                                            os.write(buf, 0, r);
                                        }
                                        os.flush();
                                    }
                                }
                            }
                        }
                    }
                }

                if (!found) {
                    finishWithError(getString(R.string.dialog_title_failed_to_install_controls),
                            "controls.json not found in the ZIP");
                    return;
                }

                finish(getString(R.string.dialog_title_controls_installed), null);

            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_install_controls), e.toString());
            }
        });
    }

    // -------------------- EXPORT CONTROLS FROM INSTANCE --------------------

    private void doExportControlsFromInstance(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_exporting_controls);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        Uri outUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI);

        if (instanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        if (outUri == null) { finishWithError(taskTitle, "Output URI is missing"); return; }

        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (gameInstance == null) { finishWithError(taskTitle, "Game instance not found: " + instanceName); return; }

        executorService.submit(() -> {
            try {
                File controlsDir = new File(gameInstance.getGamePath(), "controls");

                if (!controlsDir.exists()) {
                    // Nothing to export — default layout, controls/ was never created
                    finish(getString(R.string.dialog_title_controls_export_skipped_default), null);
                    return;
                }

                try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    ZipUtils.zipDirectoryToStream(controlsDir, os);
                }

                finish(getString(R.string.dialog_title_controls_exported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_export_controls), e.toString());
            }
        });
    }

    // -------------------- IMPORT CUSTOM DRIVER --------------------

    private void doImportCustomDriver(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_importing_driver);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        Uri driverUri = intent.getParcelableExtra(EXTRA_DRIVER_URI);
        if (driverUri == null) {
            finishWithError(taskTitle, "Driver URI is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                String destPath = AppStorage.requireSingleton().getHomePath()
                        + "/" + C.deps.CUSTOM_DRIVER;
                File destFile = new File(destPath);

                File parent = destFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                try (InputStream is = getContentResolver().openInputStream(driverUri);
                     OutputStream os = new java.io.FileOutputStream(destFile, false)) {
                    if (is == null) throw new IllegalStateException("openInputStream returned null");
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        os.write(buf, 0, r);
                    }
                }

                finish(getString(R.string.dialog_title_driver_imported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_import_driver), e.toString());
            }
        });
    }

    // -------------------- EXPORT CUSTOM DRIVER --------------------

    private void doExportCustomDriver(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_exporting_driver);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        Uri outUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI);
        if (outUri == null) {
            finishWithError(taskTitle, "Output URI is missing");
            return;
        }

        executorService.submit(() -> {
            try {
                String srcPath = AppStorage.requireSingleton().getHomePath()
                        + "/" + C.deps.CUSTOM_DRIVER;
                File srcFile = new File(srcPath);

                if (!srcFile.exists()) {
                    finishWithError(taskTitle, "Custom driver file not found: " + srcPath);
                    return;
                }

                try (InputStream is = new java.io.FileInputStream(srcFile);
                     OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        os.write(buf, 0, r);
                    }
                }

                finish(getString(R.string.dialog_title_driver_exported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_export_driver), e.toString());
            }
        });
    }

    // -------------------- EXPORT LOG --------------------

    private void doExportLog(Intent intent) {
        String taskTitle = getString(R.string.dialog_title_exporting_log);

        startForeground(NOTIFICATION_ID, buildNotification(taskTitle));
        this.taskState.postValue(new TaskState(taskTitle, null, -1, 0, false, false));

        String instanceName = intent.getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        Uri outUri = intent.getParcelableExtra(EXTRA_OUTPUT_URI);

        if (instanceName == null) { finishWithError(taskTitle, "Game instance name is missing"); return; }
        if (outUri == null) { finishWithError(taskTitle, "Output URI is missing"); return; }

        GameInstance gi = GameInstanceManager.requireSingleton().getInstanceByName(instanceName);
        if (gi == null) { finishWithError(taskTitle, "Game instance not found: " + instanceName); return; }

        executorService.submit(() -> {
            try {
                if (!hasAnyLogFiles(gi)) {
                    finishWithError(taskTitle, "No log files found");
                    return;
                }

                try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    writeLogReportZip(gi, os);
                }

                finish(getString(R.string.dialog_title_log_exported), null);
            } catch (Exception e) {
                finishWithError(getString(R.string.dialog_title_failed_to_export_log), e.toString());
            }
        });
    }

    private static boolean hasAnyLogFiles(GameInstance gi) {
        File nativeLog = new File(gi.getGamePath() + "/native.log");
        File gameLog = new File(gi.getGamePath() + "/log.txt");
        File launcherLog = new File(AppStorage.requireSingleton().getHomePath() + "/" + CrashHandler.LOG_FILE_NAME);
        File lastLauncherLog = new File(AppStorage.requireSingleton().getHomePath() + "/" + CrashHandler.LAST_LOG_FILE_NAME);
        return nativeLog.exists() || gameLog.exists() || launcherLog.exists() || lastLauncherLog.exists();
    }

    // Builds the same diagnostic zip (report.txt + crash/native/shader/launcher logs) used
    // by both "Export logs" (SAF-picked destination) and the "Report a bug" email attachment, so
    // there is one definition of what a Zomdroid bug report contains. Caller owns/closes `os`.
    /** gi may be null - no instance installed yet. That is not the empty case but the hottest one:
     *  the user whose dependency install or instance creation is failing has no instance BY
     *  DEFINITION, and the launcher-level logcat capture is exactly where that failure is written.
     *  The zip is built from whatever exists instead of not at all. */
    public static void writeLogReportZip(GameInstance gi, OutputStream os) throws IOException {
        File launcherLog = new File(AppStorage.requireSingleton().getHomePath() + "/" + CrashHandler.LOG_FILE_NAME);
        // Crash session's logcat lives in lastlog.txt: after a native game crash the process
        // dies and the app restarts, which rotates log.txt -> lastlog.txt.
        File lastLauncherLog = new File(AppStorage.requireSingleton().getHomePath() + "/" + CrashHandler.LAST_LOG_FILE_NAME);
        // Native crash handler dump (SIGSEGV/SIGBUS/SIGILL/SIGFPE) written into the game dir.
        File crashFile = gi == null ? null : new File(gi.getGamePath() + "/crash.txt");
        // Persistent mirror of native stdout/stderr (box64 SEGV/BT reports, NG probes) —
        // survives crashes/restarts, unlike the rotating logcat.
        File nativeLog = gi == null ? null : new File(gi.getGamePath() + "/native.log");
        // The game's own log (Deephall writes it into its working dir, the game dir): Java
        // exceptions land here and nowhere else. log.0.txt is the run before, as the game rotates
        // the file at start, so a crash followed by one more launch is still in the report.
        File gameLog = gi == null ? null : new File(gi.getGamePath() + "/log.txt");
        File previousGameLog = gi == null ? null : new File(gi.getGamePath() + "/log.0.txt");
        // NG_GL4ES shader diagnostics: full source + driver log of shaders that failed to
        // compile/link (up to 10 programs) + GL trace. Written by libng_gl4es into files/.
        File failedShaders = new File(AppStorage.requireSingleton().getHomePath() + "/failed_shaders.txt");
        File glTrace = new File(AppStorage.requireSingleton().getHomePath() + "/gl_trace.txt");
        // Everything below is added by NAME, never by walking a directory, and that is a property
        // worth keeping: the app's home holds NG_GL4ES's ETC2 texture cache
        // (C.NGG_ETC2_CACHE_DIR), which is hundreds of megabytes - 879 MB on one device. A report
        // assembled by sweeping files/ would be unsendable. Add new files one by one.
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os, 256 * 1024))) {
            // report.txt — device / build metadata
            // The report must describe the instance it is about, not the app-wide defaults.
            // gi is null when no instance is installed yet; a null name reads the global values,
            // which is exactly what the report used to print in that case.
            com.zomdroid.game.InstanceSettings settings =
                    new com.zomdroid.game.InstanceSettings(gi != null ? gi.getName() : null);
            LauncherPreferences.VulkanDriver driver = settings.getVulkanDriver();
            String driverStr = driver.libName != null
                    ? driver.name() + " (" + driver.libName + ")"
                    : "system default";

            zos.putNextEntry(new ZipEntry("report.txt"));
            writeLogUtf8(zos, "=== Deephall Bug Report ===\n");
            writeLogUtf8(zos, "Device   : " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
            writeLogUtf8(zos, "Android  : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            // The git id is what actually identifies the build: versionName/versionCode repeat
            // across test builds (1.4.7 and 1.4.7v4 both reported as "1.4.7 (147)"), so without it
            // a report cannot say which binary produced it. "+" means the tree had uncommitted work.
            writeLogUtf8(zos, "Deephall : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE
                    + ", " + BuildConfig.GIT_BUILD_ID + ")\n");
            // Which instance this report is even about: the instance and its preset.
            writeLogUtf8(zos, gi == null
                    ? "Instance : (none installed)\n"
                    : "Instance : " + gi.getName()
                        + " (" + gi.getPresetName() + ", build " + gi.getBuildVersion() + ")\n");
            writeLogUtf8(zos, "Renderer : " + settings.getRenderer().name() + "\n");
            writeLogUtf8(zos, "Driver   : " + driverStr + "\n");
            // The two questions every NG_GL4ES "it just closes" report starts with: how much RAM
            // does the device have, and did the player ever apply the Build 42 JVM preset. Both
            // used to require digging through lastlog.txt, which is not always in the archive.
            writeLogUtf8(zos, "RAM      : " + readRamSummary() + "\n");
            // The rest of the memory treatment, in the same place as the RAM figure: the texture
            // budget toggle and the resolution the renderer actually draws at.
            writeLogUtf8(zos, "Memory   : saver " + (settings.isMemorySaver() ? "ON" : "off")
                    + ", render scale " + String.format(Locale.US, "%.2f", settings.getRenderScale()) + "\n");
            String jvmArgs = LauncherPreferences.squashWhitespace(settings.getJvmArgs());
            writeLogUtf8(zos, "JVM args : " + (jvmArgs.isEmpty() ? "(none)" : jvmArgs)
                    + "  [" + LauncherPreferences.describeJvmArgsPreset(jvmArgs) + "]\n");
            // Env vars matter as much as the JVM args: knobs like LIBGL_SHRINK, LIBGL_TEXBUDGET and
            // ZINK_DEBUG travel around chats as folklore, and without this line a report gives no
            // way to tell an actual finding from something the player pasted in on someone's advice.
            String envVars = LauncherPreferences.squashWhitespace(settings.getEnvVars());
            writeLogUtf8(zos, "Env vars : " + (envVars.isEmpty() ? "(none)" : envVars) + "\n");
            writeLogUtf8(zos, "===========================\n");
            zos.closeEntry();

            // Original log files, verbatim — each kept whole in its own entry
            addFileToZip(zos, crashFile, "crash.txt");
            addFileToZip(zos, nativeLog, "native.log");
            addFileToZip(zos, gameLog, "game-log.txt");
            addFileToZip(zos, previousGameLog, "game-log.0.txt");
            addFileToZip(zos, failedShaders, "failed_shaders.txt");
            addFileToZip(zos, glTrace, "gl_trace.txt");
            addFileToZip(zos, launcherLog, "log.txt");
            addFileToZip(zos, lastLauncherLog, "lastlog.txt");
        }
    }

    // Total/available RAM straight from /proc/meminfo. ActivityManager.MemoryInfo would give the
    // same two numbers plus lowMemory, but writeLogReportZip is static and has no Context, and
    // these two are what separates "killed for memory" from "crashed on its own". Note MemTotal is
    // what the kernel manages, so an 8 GB device reports ~7.4 GB — treat it as the marketing size
    // rounded up, not as an exact figure.
    private static String readRamSummary() {
        long totalKb = -1, availKb = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) totalKb = parseMeminfoKb(line);
                else if (line.startsWith("MemAvailable:")) availKb = parseMeminfoKb(line);
                if (totalKb >= 0 && availKb >= 0) break;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read /proc/meminfo: " + e);
        }
        if (totalKb < 0) return "unavailable";
        String s = formatGb(totalKb) + " total";
        if (availKb >= 0) s += ", " + formatGb(availKb) + " available";
        return s;
    }

    // "MemTotal:        7654321 kB" -> 7654321, or -1 if the line is not shaped as expected.
    private static long parseMeminfoKb(String line) {
        try {
            return Long.parseLong(line.split("\\s+")[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String formatGb(long kb) {
        return String.format(Locale.US, "%.1f GB", kb / 1048576.0);
    }

    // Adds a file to the zip under entryName. No-op if the file is missing.
    private static void addFileToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        if (file == null || !file.exists()) return;
        zos.putNextEntry(new ZipEntry(entryName));
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) != -1) zos.write(buf, 0, r);
        }
        zos.closeEntry();
    }

    private static void writeLogUtf8(OutputStream os, String s) throws IOException {
        os.write(s.getBytes("UTF-8"));
    }

    private static void appendFileToStream(OutputStream os, File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) != -1) {
                os.write(buf, 0, r);
            }
        }
    }

    // -------------------- TASK STATE / NOTIFICATION --------------------

    private void finish(String title, String message) {
        taskRunning = false;
        userTaskRunning = false;
        this.taskState.postValue(new TaskState(title, message, -1, 0, true, false));
    }

    private void finishWithError(String title, String error) {
        taskRunning = false;
        Log.e(LOG_TAG, error);
        userTaskRunning = false;
        this.taskState.postValue(new TaskState(title, error, -1, 0, false, true));
    }


    // Every archive extraction reports progress through FileUtils, which has no text to give -
    // its ticks carry null and rely on onProgressUpdate keeping the previous message. That only
    // works if a message was SET first, and most tasks never set one: the instance-creation
    // dialog showed a bare bar under its title for exactly this reason. One announcement before
    // each extraction; repeated calls are harmless (message calls bypass the tick throttle).
    private void announceExtraction() {
        onProgressUpdate(getString(R.string.extracting), -1, 0);
    }

    private void installGameFromZip(GameInstance gameInstance, Uri zipUri) throws IOException {
        ContentResolver contentResolver = getApplicationContext().getContentResolver();
        try (InputStream inputStream = contentResolver.openInputStream(zipUri)) {
            long fileSize = FileUtils.queryFileSize(contentResolver, zipUri);
            announceExtraction();
            FileUtils.extractZipToDisk(inputStream, gameInstance.getGamePath(), this, fileSize);
        }
    }

    @Override
    public void onTimeout(int startId) {
        super.onTimeout(startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private Notification buildNotification(String title) {
        return buildNotification(title, new Intent(this, LauncherActivity.class));
    }

    // The activity the app icon opens. In the standalone Deephall build that is the start screen,
    // which is where a tap on the "preparing" notification has to lead: the launcher is hidden.
    private Intent launchActivityIntent() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent == null || launchIntent.getComponent() == null)
            return new Intent(this, LauncherActivity.class);
        return new Intent().setComponent(launchIntent.getComponent());
    }

    private Notification buildNotification(String title, Intent notificationIntent) {
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setAutoCancel(false);
        return notificationBuilder.build();
    }

    @Override
    public void onProgressUpdate(String message, int progress, int progressMax) {
        // The throttle drops the whole event, not just a redraw, so a call carrying a new message
        // that lands inside the window would lose that message for good - the next call is a bare
        // percentage tick and could never bring it back. Only rate-limit the ticks.
        if (message == null && System.currentTimeMillis() - lastProgressUpdateMs < 500) return;
        lastProgressUpdateMs = System.currentTimeMillis();

        TaskState currentState = this.taskState.getValue();
        this.taskState.postValue(new TaskState(
                currentState == null ? null : currentState.title,
                // Carried forward exactly like the title above. Extraction reports progress through
                // FileUtils, which has no text to give and passes null; treating that as "clear the
                // line" is what wiped the "Extracting..." set moments earlier and left the dialog
                // with a bare bar. The notification never had the bug - it only calls
                // setContentText when the message is non-null - so the two disagreed on the same
                // event.
                message != null ? message : (currentState == null ? null : currentState.message),
                progress, progressMax, false, false));

        handler.post(() -> {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (message != null) {
                notificationBuilder.setContentText(message);
            }
            if (progress < 0)
                notificationBuilder.setProgress(0, 0, true);
            else
                notificationBuilder.setProgress(progressMax, progress, false);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        });
    }

    public LiveData<TaskState> getTaskState() {
        return taskState;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    public String getCurrentInstallPresetName() {
        return currentInstallPresetName;
    }

    public String getCurrentInstanceName() {
        return currentInstanceName;
    }

    public String getCurrentGpuVendor() {
        return currentGpuVendor;
    }

    public long getCurrentRunToken() {
        return currentRunToken;
    }

    public class LocalBinder extends Binder {
        public InstallerService getService() {
            return InstallerService.this;
        }
    }

    // -------------------- ENUMS / DATA CLASSES --------------------

    public enum Task {
        CREATE_GAME_INSTANCE,
        DELETE_GAME_INSTANCE,
        INSTALL_DEPENDENCIES,
        INSTALL_CONTROLS_TO_INSTANCE,
        EXPORT_CONTROLS_FROM_INSTANCE,
        IMPORT_CUSTOM_DRIVER,
        EXPORT_CUSTOM_DRIVER,
        EXPORT_LOG,
        // Appended, not inserted: tasks travel in intents as their ordinal.
        PREPARE_BUNDLED_GAME
    }

    public static class TaskState {
        public final String title;
        public final String message;
        public final int progress;
        public final int progressMax;
        public final boolean isFinished;
        public final boolean isFinishedWithError;

        public TaskState(String title, String message, int progress, int progressMax,
                         boolean isFinished, boolean isFinishedWithError) {
            this.title = title;
            this.message = message;
            this.progress = progress;
            this.progressMax = progressMax;
            this.isFinished = isFinished;
            this.isFinishedWithError = isFinishedWithError;
        }
    }
}
