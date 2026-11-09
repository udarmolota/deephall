package com.zomdroid;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Tells players a new Deephall is out. Once a day, in the background (UpdateCheckJobService) and on
 * the start screen, it asks GitHub for the latest release of udarmolota/deephall. When that is newer
 * than the installed version it posts one notification for that version - which also puts the dot on
 * the app icon - and the game's loading screen shows a line with a link to the release.
 *
 * <p>GitHub's "latest release" leaves out pre-releases and drafts, so a test build published as a
 * pre-release reaches only the testers it is given to, not every player.
 */
public final class UpdateChecker {
    private static final String LOG_TAG = UpdateChecker.class.getName();
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/udarmolota/deephall/releases/latest";

    private static final String PREFS = "deephall_updates";
    private static final String KEY_LATEST_VERSION = "latestVersion";
    private static final String KEY_LATEST_URL = "latestUrl";
    private static final String KEY_CHECK_DAY = "checkDay";
    private static final String KEY_NOTIFIED_VERSION = "notifiedVersion";
    private static final String KEY_ASKED_FOR_NOTIFICATIONS = "askedForNotifications";

    private static final String CHANNEL_ID = "deephall_updates";
    private static final int NOTIFICATION_ID = 0xDEE9;
    private static final int JOB_ID = 0xDEE9;

    private UpdateChecker() {}

    /** On every start: make sure the daily background check is scheduled, and check now if not done today. */
    public static void onAppStart(Context context) {
        // Never let the update check stop the game from starting: a refused job only costs the background check
        try {
            scheduleDailyCheck(context);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Could not schedule the daily update check", e);
        }
        new Thread(() -> checkIfDue(context.getApplicationContext()), "deephall-update-check").start();
    }

    /**
     * Asks once, ever, for permission to post notifications (Android 13+): without it the new-version
     * notification and the dot on the icon cannot appear. Best asked on the first start, while the
     * game unpacks and the player is waiting anyway.
     */
    public static void askForNotificationsOnce(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        SharedPreferences prefs = prefs(activity);
        if (prefs.getBoolean(KEY_ASKED_FOR_NOTIFICATIONS, false)) return;
        prefs.edit().putBoolean(KEY_ASKED_FOR_NOTIFICATIONS, true).apply();
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode);
        }
    }

    /** The newer version last seen on GitHub, or null when the installed one is up to date. */
    @Nullable
    public static String newerVersion(Context context) {
        String latest = prefs(context).getString(KEY_LATEST_VERSION, null);
        return latest != null && compareVersions(latest, BuildConfig.VERSION_NAME) > 0 ? latest : null;
    }

    @Nullable
    public static String releaseUrl(Context context) {
        return prefs(context).getString(KEY_LATEST_URL, null);
    }

    private static void scheduleDailyCheck(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
        // Not persisted: that would need the boot permission. After a reboot the next start schedules it again.
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .build();
        scheduler.schedule(job);
    }

    /** At most one attempt a day; the day is recorded before the network call, so being offline costs one try. */
    static void checkIfDue(Context context) {
        SharedPreferences prefs = prefs(context);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (today.equals(prefs.getString(KEY_CHECK_DAY, null))) return;
        prefs.edit().putString(KEY_CHECK_DAY, today).apply();
        try {
            JSONObject release = fetchLatestRelease();
            String tag = release.getString("tag_name");
            String version = tag.replaceFirst("^[vV]", "");
            prefs.edit()
                    .putString(KEY_LATEST_VERSION, version)
                    .putString(KEY_LATEST_URL, release.getString("html_url"))
                    .apply();
            notifyIfNew(context);
        } catch (Exception e) {
            Log.i(LOG_TAG, "Update check failed, will try again tomorrow: " + e);
        }
    }

    private static void notifyIfNew(Context context) {
        String newer = newerVersion(context);
        SharedPreferences prefs = prefs(context);
        if (newer == null || newer.equals(prefs.getString(KEY_NOTIFIED_VERSION, null))) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return; // The loading screen still shows the line
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.deephall_update_channel), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);

        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl(context)));
        PendingIntent pending = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.deephall_update_title, newer))
                .setContentText(context.getString(R.string.deephall_update_text))
                .setContentIntent(pending)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification.build());
            prefs.edit().putString(KEY_NOTIFIED_VERSION, newer).apply();
        } catch (SecurityException e) {
            Log.i(LOG_TAG, "Not allowed to post the update notification: " + e);
        }
    }

    private static JSONObject fetchLatestRelease() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    /** Dotted versions compared number by number ("0.1.10" > "0.1.9"); a suffix after the digits is ignored. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-+ ]"), pb = b.split("[.\\-+ ]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? leadingNumber(pa[i]) : 0;
            int y = i < pb.length ? leadingNumber(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int leadingNumber(String s) {
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
