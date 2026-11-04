package technology.rocketjump.mountaincore.misc;

import org.pmw.tinylog.Logger;

/**
 * Writes to the game log where the main thread is when a frame takes too long, so a freeze reported from a
 * phone shows the line of code it is stuck on (a phone gives no way to take a thread dump from outside).
 * It only reads and logs: once at 10 seconds into a stall and again at 30, so a loop shows up as the same
 * place twice. It never stops or changes the game. A stall is also logged while the app is in the
 * background, where nothing is drawn; those entries show the main thread waiting and can be ignored.
 */
public final class FreezeWatchdog {

	private static final long[] REPORT_AFTER_MILLIS = {10_000, 30_000};
	private static final long CHECK_EVERY_MILLIS = 1_000;

	private static volatile long lastFrameMillis = System.currentTimeMillis();
	private static volatile Thread mainThread;

	private FreezeWatchdog() {
	}

	/**
	 * Called by the main thread at the start of every frame. The first call starts the watcher, so the
	 * game's loading before the first frame is never reported as a freeze.
	 */
	public static void frameStarted() {
		lastFrameMillis = System.currentTimeMillis();
		if (mainThread == null) {
			mainThread = Thread.currentThread();
			Thread watcher = new Thread(FreezeWatchdog::watch, "Freeze watchdog");
			watcher.setDaemon(true);
			watcher.start();
		}
	}

	private static void watch() {
		long reportedForFrame = -1;
		int reportsForFrame = 0;
		while (true) {
			try {
				Thread.sleep(CHECK_EVERY_MILLIS);
			} catch (InterruptedException e) {
				return;
			}
			long frame = lastFrameMillis;
			if (frame != reportedForFrame) {
				reportedForFrame = frame;
				reportsForFrame = 0;
			}
			long stalledFor = System.currentTimeMillis() - frame;
			if (reportsForFrame < REPORT_AFTER_MILLIS.length && stalledFor >= REPORT_AFTER_MILLIS[reportsForFrame]) {
				reportsForFrame++;
				report(stalledFor);
			}
		}
	}

	private static void report(long stalledFor) {
		StringBuilder text = new StringBuilder("FREEZE: the main thread has not finished a frame for ")
				.append(stalledFor / 1000).append(" seconds, it is at:");
		for (StackTraceElement element : mainThread.getStackTrace()) {
			text.append(System.lineSeparator()).append("\tat ").append(element);
		}
		Logger.error(text.toString());
	}
}
