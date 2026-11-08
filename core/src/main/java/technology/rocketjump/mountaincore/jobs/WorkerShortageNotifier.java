package technology.rocketjump.mountaincore.jobs;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.GameContextAware;
import technology.rocketjump.mountaincore.jobs.model.Job;
import technology.rocketjump.mountaincore.jobs.model.JobState;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.misc.VectorUtils;
import technology.rocketjump.mountaincore.settlement.notifications.Notification;
import technology.rocketjump.mountaincore.ui.i18n.I18nTranslator;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells the player once about each shortage that keeps jobs waiting, and again only if it comes back after being solved.
 */
@Singleton
public class WorkerShortageNotifier implements Telegraph, GameContextAware {

	// A tool that is only in somebody's hands for a moment should not raise an alarm
	private static final int HOURS_BEFORE_NOTIFYING = 6;
	// A shortage that keeps coming and going (fishing jobs appear and vanish) is told again only after a week
	private static final double HOURS_BETWEEN_SAME_NOTIFICATION = 7 * 24;
	private final Map<WorkerAvailability.Shortage, Double> lastNotified = new HashMap<>();

	private final MessageDispatcher messageDispatcher;
	private final JobStore jobStore;
	private final WorkerAvailability workerAvailability;
	private final I18nTranslator i18nTranslator;
	private final Map<WorkerAvailability.Shortage, Integer> hoursSeen = new HashMap<>();
	private GameContext gameContext;

	@Inject
	public WorkerShortageNotifier(MessageDispatcher messageDispatcher, JobStore jobStore,
								  WorkerAvailability workerAvailability, I18nTranslator i18nTranslator) {
		this.messageDispatcher = messageDispatcher;
		this.jobStore = jobStore;
		this.workerAvailability = workerAvailability;
		this.i18nTranslator = i18nTranslator;

		messageDispatcher.addListener(this, MessageType.HOUR_ELAPSED);
	}

	@Override
	public boolean handleMessage(Telegram msg) {
		if (msg.message == MessageType.HOUR_ELAPSED) {
			if (gameContext != null) {
				onHourElapsed();
			}
			return false;
		}
		throw new IllegalArgumentException("Unexpected message type " + msg.message + " received by " + this.getClass().getSimpleName() + ", " + msg);
	}

	private void onHourElapsed() {
		Map<WorkerAvailability.Shortage, Job> shortages = new LinkedHashMap<>();
		for (Job job : jobStore.getCollectionByState(JobState.ASSIGNABLE).getAll()) {
			workerAvailability.check(job).ifPresent(shortage -> shortages.putIfAbsent(shortage, job));
		}

		hoursSeen.keySet().retainAll(shortages.keySet());
		shortages.forEach((shortage, job) -> {
			double now = gameContext.getGameClock().getCurrentGameTime();
			if (hoursSeen.merge(shortage, 1, Integer::sum) == HOURS_BEFORE_NOTIFYING
					&& now - lastNotified.getOrDefault(shortage, -Double.MAX_VALUE) >= HOURS_BETWEEN_SAME_NOTIFICATION) {
				lastNotified.put(shortage, now);
				Notification notification = new Notification(shortage.problem().notificationType, VectorUtils.toVector(job.getJobLocation()), null);
				workerAvailability.textReplacements(shortage, i18nTranslator).forEach(notification::addTextReplacement);
				messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION, notification);
			}
		});
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {
		hoursSeen.clear();
		lastNotified.clear();
		gameContext = null;
	}
}
