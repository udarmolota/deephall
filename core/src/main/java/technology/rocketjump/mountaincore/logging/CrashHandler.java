package technology.rocketjump.mountaincore.logging;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.badlogic.gdx.Gdx;
import com.google.inject.ProvisionException;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.persistence.UserPreferences;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static technology.rocketjump.mountaincore.rendering.camera.GlobalSettings.VERSION;

public class CrashHandler {

	private static final String BUG_REPORT_EMAIL = "210478@mail.ru";
	// A mailto link stops working when it grows past the limit of the operating system
	private static final int MAX_STACK_TRACE_IN_EMAIL = 1200;
	private static final String LOG_FILENAME = "log.txt";

	public static void displayCrashDialog(Throwable e) {

//		Display.destroy(); //ensure opengl is destroyed todo


		Throwable exceptionToDisplay = e;
		if (e instanceof ProvisionException) {
			exceptionToDisplay = e.getCause();
		}

		Object[] options = new Object[] {
				"Copy to Clipboard",
				"Report by Email",
				"Close"
		};
		try {
			String stackTrace = ExceptionUtils.getStackTrace(exceptionToDisplay);
			int lastNewLineIndex = 0;
			int lineCount = 5;
			while (lineCount > 0 && lastNewLineIndex != -1) {
				lastNewLineIndex = stackTrace.indexOf('\n', lastNewLineIndex + 1);
				lineCount--;
			}

			if (lastNewLineIndex > 0 && lastNewLineIndex < stackTrace.length()) {
				stackTrace = stackTrace.substring(0, lastNewLineIndex);
			}

			int result = JOptionPane.showOptionDialog(null, stackTrace, "Error - Game",
					JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[0]);

			if (result == 0) {
				String clipboardText = getJsonPayload(e).toString(SerializerFeature.PrettyFormat);
				ClipboardUtils.copyToClipboard(clipboardText);
			} else if (result == 1) {
				openBugReportEmail(stackTrace);
			}
		} catch (RuntimeException runtimeException) {
			CrashHandler.logCrash(new RuntimeException("Error displaying exception dialog", runtimeException));
		}
	}

	// Opens the mail app of the player, who sees the message and decides whether to send it
	private static void openBugReportEmail(String stackTrace) {
		try {
			String subject = "Deephall " + VERSION + " crash";
			String body = "Please attach " + LOG_FILENAME + " from the folder that has just opened.\n\n" + StringUtils.left(stackTrace, MAX_STACK_TRACE_IN_EMAIL);
			Desktop desktop = Desktop.getDesktop();
			desktop.mail(new URI("mailto:" + BUG_REPORT_EMAIL + "?subject=" + encodeForEmail(subject) + "&body=" + encodeForEmail(body)));
			desktop.open(new File(LOG_FILENAME).getAbsoluteFile().getParentFile());
		} catch (IOException | URISyntaxException | UnsupportedOperationException e) {
			Logger.error("Failed to open bug report email: " + e.getMessage());
		}
	}

	private static String encodeForEmail(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
	}

	public static void logCrash(Throwable exception) {
		Logger.error(exception);
	}

	public static JSONObject getJsonPayload(Throwable exception) {
		JSONObject payload = new JSONObject();
		payload.put("gameVersion", VERSION.toString());
		payload.put("displaySettings", Gdx.graphics.getDisplayMode(Gdx.graphics.getMonitor()).toString());
		payload.put("stackTrace", ExceptionUtils.getStackTrace(exception));
		payload.put("preferencesJson", removeKeybindings(UserPreferences.preferencesJson));
		return payload;
	}

	private static String removeKeybindings(String preferencesJson) {
		JSONObject preferences = JSONObject.parseObject(preferencesJson);
		JSONObject filtered = new JSONObject(true);
		for (String key : preferences.keySet()) {
			if (EnumUtils.getEnum(UserPreferences.PreferenceKey.class, key) != null) {
				filtered.put(key, preferences.getString(key));
			}
		}
		return filtered.toJSONString();
	}

}
