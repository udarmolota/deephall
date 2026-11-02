package com.zomdroid;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.zomdroid.databinding.ActivityDeephallStartBinding;
import com.zomdroid.input.GamepadManager;

/**
 * The standalone Deephall app's entry point: the only launcher activity of the debug and release builds.
 *
 * <p>Asks InstallerService to prepare the game (PREPARE_BUNDLED_GAME: dependencies, then the
 * bundled instance - see {@link BundledGame}), shows its progress full screen, and starts
 * GameActivity the way LauncherFragment does once it is done. On failure the error stays on screen
 * with a button into the launcher, which is hidden otherwise and still has the logs and the bug
 * report.
 */
public class DeephallStartActivity extends AppCompatActivity {
    private static final String LOG_TAG = DeephallStartActivity.class.getName();
    private static final String STATE_RUN_TOKEN = "runToken";
    private static final String STATE_ERROR_TITLE = "errorTitle";
    private static final String STATE_ERROR_MESSAGE = "errorMessage";

    // The run this process has started. A token restored from a saved state but not equal to this
    // one belongs to a process that is gone, and its run died with it.
    private static long startedRunToken;

    private ActivityDeephallStartBinding binding;
    private InstallerService installerService;
    private boolean isInstallerServiceBound;
    private long runToken;
    private boolean failed;
    private String errorTitle;
    private String errorMessage;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            installerService = ((InstallerService.LocalBinder) service).getService();
            isInstallerServiceBound = true;
            installerService.getTaskState().observe(DeephallStartActivity.this,
                    DeephallStartActivity.this::handleTaskState);
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.e(LOG_TAG, "Connection to installer service has been lost");
            isInstallerServiceBound = false;
            installerService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeephallStartBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.startOpenLauncherMb.setOnClickListener(v -> openLauncher());

        // LauncherActivity does this at app start, and GameActivity relies on it. The launcher is
        // skipped now, so it has to happen here.
        GamepadManager.loadCustomMapping(this);

        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(STATE_ERROR_MESSAGE)) {
                showError(savedInstanceState.getString(STATE_ERROR_TITLE),
                        savedInstanceState.getString(STATE_ERROR_MESSAGE));
                return;
            }
            long savedToken = savedInstanceState.getLong(STATE_RUN_TOKEN, 0);
            if (savedToken != 0 && savedToken == startedRunToken) runToken = savedToken;
        }

        // Recreated while our run is still going: follow it rather than start it again.
        if (runToken == 0) {
            runToken = SystemClock.elapsedRealtimeNanos();
            startedRunToken = runToken;
            Intent installerIntent = new Intent(this, InstallerService.class);
            installerIntent.putExtra(InstallerService.EXTRA_COMMAND,
                    InstallerService.Task.PREPARE_BUNDLED_GAME.ordinal());
            installerIntent.putExtra(InstallerService.EXTRA_RUN_TOKEN, runToken);
            startForegroundService(installerIntent);
        }
        // No BIND_AUTO_CREATE: the start above creates the service, and the binding connects as
        // soon as it exists, whichever of the two the system gets to first.
        bindService(new Intent(this, InstallerService.class), installerServiceConnection, 0);
    }

    private void handleTaskState(InstallerService.TaskState state) {
        if (state == null || installerService == null || failed) return;
        // Anything else is what an earlier task left in the service - including an earlier run of
        // this same task - and must not be read as this run finishing.
        if (installerService.getCurrentTask() != InstallerService.Task.PREPARE_BUNDLED_GAME
                || installerService.getCurrentRunToken() != runToken) return;

        if (state.isFinished) {
            releaseInstallerService();
            launchGame();
        } else if (state.isFinishedWithError) {
            releaseInstallerService();
            showError(state.title, state.message);
        } else {
            showProgress(state.message, state.progress, state.progressMax);
        }
    }

    private void showProgress(String message, int progress, int progressMax) {
        if (message != null) {
            binding.startMessageTv.setText(message);
            binding.startMessageTv.setVisibility(View.VISIBLE);
        }
        if (progress < 0) {
            binding.startProgressLpi.setIndeterminate(true);
        } else {
            binding.startProgressLpi.setIndeterminate(false);
            binding.startProgressLpi.setMax(progressMax);
            binding.startProgressLpi.setProgress(progress);
        }
    }

    private void showError(String title, String message) {
        failed = true;
        errorTitle = title != null ? title : getString(R.string.deephall_prepare_failed);
        errorMessage = message != null ? message : "";
        binding.startTitleTv.setText(errorTitle);
        binding.startMessageTv.setVisibility(View.GONE);
        binding.startProgressLpi.setVisibility(View.GONE);
        binding.startErrorTv.setText(errorMessage);
        binding.startErrorSv.setVisibility(View.VISIBLE);
        binding.startOpenLauncherMb.setVisibility(View.VISIBLE);
    }

    // Same intent and flags as LauncherFragment#launchGame. CLEAR_TASK takes this screen out of
    // the back stack, so Back from the game does not land here.
    private void launchGame() {
        Intent intent = new Intent(this, GameActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(GameActivity.EXTRA_GAME_INSTANCE_NAME, BundledGame.INSTANCE_NAME);
        startActivity(intent);
        finish();
    }

    private void openLauncher() {
        startActivity(new Intent(this, LauncherActivity.class));
        finish();
    }

    // Stops following the service and lets it stop, unless something has started it again since.
    private void releaseInstallerService() {
        InstallerService service = installerService;
        if (service != null) service.getTaskState().removeObservers(this);
        if (isInstallerServiceBound) {
            unbindService(installerServiceConnection);
            isInstallerServiceBound = false;
        }
        installerService = null;
        if (service != null) service.stopUnlessRestarted();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_RUN_TOKEN, runToken);
        if (failed) {
            outState.putString(STATE_ERROR_TITLE, errorTitle);
            outState.putString(STATE_ERROR_MESSAGE, errorMessage);
        }
    }

    @Override
    protected void onDestroy() {
        // Leaving mid-run (Back, or the system) only drops the binding: the service keeps
        // preparing the game, and the next start picks the finished instance up.
        if (installerService != null) installerService.getTaskState().removeObservers(this);
        if (isInstallerServiceBound) {
            unbindService(installerServiceConnection);
            isInstallerServiceBound = false;
        }
        super.onDestroy();
    }
}
