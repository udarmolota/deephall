package technology.rocketjump.mountaincore.persistence;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.GameState;
import technology.rocketjump.mountaincore.gamecontext.Updatable;
import technology.rocketjump.mountaincore.messaging.MessageType;

import java.io.File;

/**
 * Extra autosaves on the phone (-Dmountaincore.android=true), where Android kills a game in the background far
 * more often than a PC loses one, and the regular autosave comes only once per game day:
 * - when the app goes to the background: the launcher (GameActivity#onStop) creates the file save-request in
 *   the game folder, the working directory; the game keeps running in the background, sees it here and saves;
 * - every 15 minutes of real time as well.
 * Times are real time, so game speed and pause do not change them.
 */
@Singleton
public class PhoneAutosaver implements Updatable {

	public static final String SAVE_REQUEST_FILE_NAME = "save-request";
	private static final long CHECK_INTERVAL_MILLIS = 2_000;
	private static final long TIMED_SAVE_INTERVAL_MILLIS = 15 * 60 * 1000;

	private static final boolean ENABLED = Boolean.getBoolean("mountaincore.android");

	private final MessageDispatcher messageDispatcher;
	private final File saveRequestFile = new File(SAVE_REQUEST_FILE_NAME);
	private GameContext gameContext;
	private long lastCheckMillis;
	private long lastSaveMillis = System.currentTimeMillis();

	@Inject
	public PhoneAutosaver(MessageDispatcher messageDispatcher) {
		this.messageDispatcher = messageDispatcher;
		if (ENABLED) {
			// Left over from a session that was killed before it saw the request
			deleteRequest();
		}
	}

	@Override
	public void update(float deltaTime) {
		if (!ENABLED || gameContext == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastCheckMillis < CHECK_INTERVAL_MILLIS) {
			return;
		}
		lastCheckMillis = now;

		boolean requested = saveRequestFile.exists();
		if (requested) {
			deleteRequest();
		}
		if (requested || now - lastSaveMillis >= TIMED_SAVE_INTERVAL_MILLIS) {
			lastSaveMillis = now;
			if (!GameState.SELECT_SPAWN_LOCATION.equals(gameContext.getSettlementState().getGameState())) {
				Logger.info(requested ? "Saving: the app went to the background" : "Saving: timed autosave");
				messageDispatcher.dispatchMessage(MessageType.REQUEST_SAVE);
			}
		}
	}

	private void deleteRequest() {
		if (saveRequestFile.exists() && !saveRequestFile.delete()) {
			Logger.warn("Could not delete " + saveRequestFile.getAbsolutePath());
		}
	}

	@Override
	public boolean runWhilePaused() {
		return true;
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
		// A newly started or loaded game counts as just saved
		this.lastSaveMillis = System.currentTimeMillis();
	}

	@Override
	public void clearContextRelatedState() {
		this.gameContext = null;
	}
}
