package com.zomdroid;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Exports the Deephall saves to a zip the player picks a place for, and imports saves back: a
 * zip made by the export, or a single .save file (one copied from the desktop game, say).
 *
 * <p>No screen of its own: it opens the system file picker, does the copy off the main thread,
 * says how it went in a toast and finishes. Reached from the app icon's shortcuts and from the
 * instance card's overflow menu, with {@link #ACTION_EXPORT_SAVES}, {@link #ACTION_IMPORT_SAVES}, or
 * {@link #ACTION_SAVES}, which asks which of the two first.
 *
 * <p>An imported save never overwrites one silently: a save of the same name is first renamed
 * to name.save.bak, which the game does not list.
 */
public class SaveTransferActivity extends ComponentActivity {
    private static final String LOG_TAG = SaveTransferActivity.class.getName();

    public static final String ACTION_EXPORT_SAVES = "com.zomdroid.action.EXPORT_SAVES";
    public static final String ACTION_IMPORT_SAVES = "com.zomdroid.action.IMPORT_SAVES";
    /** Asks first whether to export or import. */
    public static final String ACTION_SAVES = "com.zomdroid.action.SAVES";

    private static final String SAVE_EXTENSION = ".save";
    private static final String BACKUP_EXTENSION = ".bak";

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), this::onExportTarget);

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onImportSource);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Recreated while the picker was open: the launcher above delivers the result.
        if (savedInstanceState != null) return;

        String action = getIntent() != null ? getIntent().getAction() : null;
        if (ACTION_EXPORT_SAVES.equals(action)) {
            startExport();
        } else if (ACTION_IMPORT_SAVES.equals(action)) {
            startImport();
        } else if (ACTION_SAVES.equals(action)) {
            // The app icon's one "Saves" shortcut: launchers show about four shortcuts, so export
            // and import share one and the player picks here.
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.shortcut_saves_long)
                    .setItems(new CharSequence[]{
                            getString(R.string.shortcut_export_saves_long),
                            getString(R.string.shortcut_import_saves_long)
                    }, (dialog, which) -> {
                        if (which == 0) startExport();
                        else startImport();
                    })
                    .setOnCancelListener(dialog -> finish())
                    .show();
        } else {
            finish();
        }
    }

    private void startExport() {
        File savesDir = savesDir();
        if (savesDir == null || listSaves(savesDir).length == 0) {
            finishWith(getString(R.string.saves_none));
            return;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        exportLauncher.launch("deephall-saves-" + date + ".zip");
    }

    private void startImport() {
        // The running game keeps its own idea of the saves folder and may write over a file
        // put there under it.
        if (GameActivity.hasGameJvm()) {
            finishWith(getString(R.string.saves_import_game_running));
            return;
        }
        if (savesDir() == null) {
            finishWith(getString(R.string.saves_no_instance));
            return;
        }
        // Zip or .save: the picker cannot filter by extension, and a .save has no MIME type
        // of its own.
        importLauncher.launch(new String[]{"*/*"});
    }

    /** The bundled instance's saves folder: user.home is the instance home, the game adds the rest. */
    private static File savesDir() {
        GameInstance instance = GameInstanceManager.requireSingleton().getInstanceByName(BundledGame.INSTANCE_NAME);
        if (instance == null) return null;
        return new File(instance.getHomePath(), "Mountaincore/saves");
    }

    private static File[] listSaves(File savesDir) {
        File[] saves = savesDir.listFiles((dir, name) -> name.endsWith(SAVE_EXTENSION));
        return saves != null ? saves : new File[0];
    }

    private void onExportTarget(Uri target) {
        if (target == null) {
            finish();
            return;
        }
        runInBackground(() -> {
            File savesDir = savesDir();
            File[] saves = listSaves(savesDir);
            try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                if (out == null) throw new IOException("No output stream for " + target);
                ZipUtils.zipDirectoryToStream(savesDir, out);
            }
            return getString(R.string.saves_exported, saves.length);
        }, R.string.saves_export_failed);
    }

    private void onImportSource(Uri source) {
        if (source == null) {
            finish();
            return;
        }
        runInBackground(() -> {
            File savesDir = savesDir();
            if (!savesDir.isDirectory() && !savesDir.mkdirs())
                throw new IOException("Cannot create " + savesDir);

            String name = displayName(source);
            int imported = 0;
            if (name != null && name.toLowerCase(Locale.ROOT).endsWith(SAVE_EXTENSION)) {
                try (InputStream in = getContentResolver().openInputStream(source)) {
                    if (in == null) throw new IOException("No input stream for " + source);
                    importSave(savesDir, name, in);
                    imported = 1;
                }
            } else {
                try (InputStream in = getContentResolver().openInputStream(source);
                     ZipInputStream zip = new ZipInputStream(in)) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        // Only the file name counts: folders in the archive are flattened, and a
                        // "../" in an entry name can never reach outside the saves folder.
                        String entryName = new File(entry.getName()).getName();
                        if (!entryName.endsWith(SAVE_EXTENSION)) continue;
                        importSave(savesDir, entryName, zip);
                        imported++;
                    }
                }
            }
            if (imported == 0) return getString(R.string.saves_import_nothing);
            return getString(R.string.saves_imported, imported);
        }, R.string.saves_import_failed);
    }

    /** Writes next to the target first, so a broken copy never takes the place of a good save. */
    private static void importSave(File savesDir, String name, InputStream in) throws IOException {
        File target = new File(savesDir, name);
        File partial = new File(savesDir, name + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw e;
        }
        if (target.exists()) {
            File backup = new File(savesDir, name + BACKUP_EXTENSION);
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
            if (!target.renameTo(backup)) throw new IOException("Cannot back up " + target);
        }
        if (!partial.renameTo(target)) throw new IOException("Cannot move " + partial + " to " + target);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception e) {
            Log.w(LOG_TAG, "No display name for " + uri, e);
        }
        return uri.getLastPathSegment();
    }

    private interface Transfer {
        String run() throws IOException;
    }

    private void runInBackground(Transfer transfer, int failureRes) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String message;
            try {
                message = transfer.run();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Save transfer failed", e);
                message = getString(failureRes, String.valueOf(e.getMessage()));
            }
            String result = message;
            main.post(() -> finishWith(result));
        }, "SaveTransfer").start();
    }

    private void finishWith(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        finish();
    }

    /** Opens the export or import from another screen. */
    public static Intent intent(android.content.Context context, String action) {
        return new Intent(context, SaveTransferActivity.class).setAction(action);
    }
}
