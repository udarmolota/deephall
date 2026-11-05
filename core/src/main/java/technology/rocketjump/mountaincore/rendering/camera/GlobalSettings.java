package technology.rocketjump.mountaincore.rendering.camera;

import com.google.common.io.Resources;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.misc.versioning.Version;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;

public class GlobalSettings {

	public static final boolean MAP_REVEALED = false;
	// Ctrl+Shift+D in the main menu toggles it; MOUNTAINCORE_DEV=1 in the environment starts with it on,
	// for a phone with no keyboard (the launcher's instance settings take environment variables)
	public static boolean DEV_MODE = "1".equals(System.getenv("MOUNTAINCORE_DEV"));
	public static final boolean CHOOSE_SPAWN_LOCATION = false;

	public static boolean USE_EDGE_SCROLLING = true;
	public static boolean ZOOM_TO_CURSOR = true;
	public static boolean TREE_TRANSPARENCY_ENABLED = true;
	public static final Version VERSION;
	public static boolean STRESS_TEST = false;

	public static boolean WEATHER_EFFECTS = true;

	static {
		String loadedVersion = "UNKNOWN 0";
		try {
			URL resourceUrl = Resources.getResource("version.txt");
			loadedVersion = Resources.toString(resourceUrl, Charset.defaultCharset());
		} catch (IOException e) {
			Logger.error(e);
		} finally {
			VERSION = new Version(loadedVersion);
		}
	}
}
