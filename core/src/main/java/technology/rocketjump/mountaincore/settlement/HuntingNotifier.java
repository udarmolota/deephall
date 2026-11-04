package technology.rocketjump.mountaincore.settlement;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.GameContextAware;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.settlement.notifications.Notification;
import technology.rocketjump.mountaincore.settlement.notifications.NotificationType;

import java.util.List;

/**
 * Tells the player why marked animals are not being hunted: nobody is a hunter, no hunter has a bow or crossbow
 * assigned, or the hunters have nothing to shoot. Once when it has lasted a few hours, then at most once a day
 * while it lasts; solving it resets the count.
 */
@Singleton
public class HuntingNotifier implements Telegraph, GameContextAware {

	// A bow that is being fetched, or ammunition on its way, should not raise an alarm
	private static final int HOURS_BEFORE_NOTIFYING = 3;
	private static final int HOURS_BETWEEN_REMINDERS = 24;

	private final MessageDispatcher messageDispatcher;
	private final SettlerTracker settlerTracker;
	private GameContext gameContext;
	private NotificationType currentProblem;
	private int hoursSeen;

	@Inject
	public HuntingNotifier(MessageDispatcher messageDispatcher, SettlerTracker settlerTracker) {
		this.messageDispatcher = messageDispatcher;
		this.settlerTracker = settlerTracker;

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
		List<Entity> targets = HuntingRules.livingHuntTargets(gameContext);
		NotificationType problem = targets.isEmpty() ? null : findProblem();
		if (problem != currentProblem) {
			currentProblem = problem;
			hoursSeen = 0;
		}
		if (problem == null) {
			return;
		}

		hoursSeen++;
		if (hoursSeen == HOURS_BEFORE_NOTIFYING || (hoursSeen > HOURS_BEFORE_NOTIFYING &&
				(hoursSeen - HOURS_BEFORE_NOTIFYING) % HOURS_BETWEEN_REMINDERS == 0)) {
			Entity target = targets.get(0);
			messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION,
					new Notification(problem, target.getLocationComponent().getWorldOrParentPosition().cpy(), null));
		}
	}

	private NotificationType findProblem() {
		List<Entity> hunters = settlerTracker.getLiving().stream().filter(HuntingRules::isHunter).toList();
		if (hunters.isEmpty()) {
			return NotificationType.HUNTER_MISSING;
		}
		List<Entity> armed = hunters.stream().filter(h -> HuntingRules.getHuntingWeapon(h, gameContext) != null).toList();
		if (armed.isEmpty()) {
			return NotificationType.HUNTING_WEAPON_MISSING;
		}
		boolean anyoneCanShoot = armed.stream().anyMatch(h -> HuntingRules.ammoCarried(h, HuntingRules.getHuntingWeapon(h, gameContext)) > 0);
		return anyoneCanShoot ? null : NotificationType.HUNTING_AMMO_MISSING;
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {
		currentProblem = null;
		hoursSeen = 0;
		gameContext = null;
	}
}
