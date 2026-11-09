package com.zomdroid;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Why the app's last processes ended, as Android recorded it (ApplicationExitInfo, Android 11+).
 *
 * <p>A bug report's logs are written by the game's own process, so when that process is killed - for
 * memory, or by a native crash in the graphics driver - the log simply stops and says nothing about
 * it: a tester on a 4 GB Mali phone sent two such reports in a row. Android keeps the reason, the
 * memory the process held when it died and, for a native crash on Android 12+, the crash dump
 * (a tombstone, in protobuf form; its function and library names are readable as plain strings).
 */
final class ExitReasons {
    private static final int MAX_EXITS = 5;

    private ExitReasons() {}

    /** The text for exit-reasons.txt, newest first, or null when there is nothing to tell. */
    static String describe(Context context) {
        List<ApplicationExitInfo> exits = exits(context);
        if (exits.isEmpty()) return null;
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder("=== How the app's last processes ended (newest first) ===\n");
        for (int i = 0; i < exits.size(); i++) {
            ApplicationExitInfo exit = exits.get(i);
            sb.append('\n').append('#').append(i + 1).append("  ").append(time.format(new Date(exit.getTimestamp()))).append('\n');
            sb.append("  reason      : ").append(reasonName(exit.getReason())).append('\n');
            sb.append("  status      : ").append(exit.getStatus())
                    .append(exit.getReason() == ApplicationExitInfo.REASON_SIGNALED || exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                            ? " (signal)" : "").append('\n');
            sb.append("  process     : ").append(exit.getProcessName()).append(" pid ").append(exit.getPid()).append('\n');
            sb.append("  importance  : ").append(exit.getImportance()).append('\n');
            sb.append("  memory (KB) : pss ").append(exit.getPss()).append(", rss ").append(exit.getRss()).append('\n');
            if (exit.getDescription() != null) sb.append("  description : ").append(exit.getDescription()).append('\n');
        }
        return sb.toString();
    }

    /** The crash dump of the newest native crash that has one (Android 12+), or null. */
    static byte[] newestNativeCrashTrace(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        for (ApplicationExitInfo exit : exits(context)) {
            if (exit.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) continue;
            try (InputStream in = exit.getTraceInputStream()) {
                if (in == null) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                return out.toByteArray();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static List<ApplicationExitInfo> exits(Context context) {
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return new ArrayList<>();
            return am.getHistoricalProcessExitReasons(null, 0, MAX_EXITS);
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY - killed by Android for memory";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE - crashed in native code (driver, GL4ES, a native library)";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH - uncaught Java exception";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED - killed by a signal (often the low memory killer)";
            case ApplicationExitInfo.REASON_ANR: return "ANR - not responding";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF - closed itself";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED - force stopped or swiped away";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN (" + reason + ")";
        }
    }
}
