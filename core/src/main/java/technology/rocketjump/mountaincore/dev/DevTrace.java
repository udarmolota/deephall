package technology.rocketjump.mountaincore.dev;

import org.pmw.tinylog.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * A development trace, off unless asked for on the command line.
 *
 * <pre>
 *   -Dmountaincore.trace=haul.log   write a line per traced event to that file
 *   -Dmountaincore.autostart=true   skip the menu and begin a new settlement
 *   -Dmountaincore.speed=3          run at that speed once the game has begun
 *   -Dmountaincore.quitAfter=1800   close the game after that many seconds
 * </pre>
 *
 * The point is to let the game play itself for an hour while nobody watches,
 * and to leave behind a file that says what the settlers were told to do.
 * Nothing here is part of the game: with no properties set, every method is a
 * cheap no-op.
 */
public class DevTrace {

	public static final boolean AUTOSTART = Boolean.parseBoolean(System.getProperty("mountaincore.autostart", "false"));
	public static final int SPEED = Integer.parseInt(System.getProperty("mountaincore.speed", "0"));
	public static final int QUIT_AFTER_SECONDS = Integer.parseInt(System.getProperty("mountaincore.quitAfter", "0"));

	private static final Path TRACE_FILE = tracePath();
	private static final boolean ENABLED = TRACE_FILE != null;
	private static BufferedWriter writer;

	private static Path tracePath() {
		String configured = System.getProperty("mountaincore.trace");
		return configured == null || configured.isBlank() ? null : Paths.get(configured).toAbsolutePath();
	}

	public static boolean enabled() {
		return ENABLED;
	}

	/** One line in the trace file, with the in-game time it happened at. */
	public static synchronized void log(String category, String message) {
		if (!ENABLED) {
			return;
		}
		try {
			if (writer == null) {
				writer = Files.newBufferedWriter(TRACE_FILE, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
			}
			writer.write(category);
			writer.write('\t');
			writer.write(message);
			writer.newLine();
			writer.flush();
		} catch (IOException e) {
			Logger.error("Could not write the development trace to " + TRACE_FILE, e);
		}
	}
}
