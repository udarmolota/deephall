package technology.rocketjump.mountaincore.settlement;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import technology.rocketjump.mountaincore.entities.components.ItemAllocationComponent;
import technology.rocketjump.mountaincore.entities.components.PerishableComponent;
import technology.rocketjump.mountaincore.entities.components.creature.HappinessComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.environment.GameClock;
import technology.rocketjump.mountaincore.environment.model.Season;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.GameContextAware;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.ui.Selectable;
import technology.rocketjump.mountaincore.settlement.notifications.Notification;
import technology.rocketjump.mountaincore.settlement.notifications.NotificationType;
import technology.rocketjump.mountaincore.ui.i18n.I18nTranslator;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Warnings before things go wrong, checked every game hour:
 * - the settlers are unhappy on average (at most once a week);
 * - a settler is close to a tantrum, with the worst cause of their mood (at most once a day per settler);
 * - winter is coming and the food will not last it (once a year, on the first day of autumn).
 * The times are not saved: after loading a game a warning that still applies can come again at once.
 */
@Singleton
public class SettlementAdvisor implements Telegraph, GameContextAware {

	private static final int COLONY_UNHAPPY_AVERAGE = -20;
	// Tantrums break out at -50 (CreatureBehaviour.TANTRUM_HAPPINESS_THRESHOLD)
	private static final int NEAR_TANTRUM = -35;
	private static final double HOURS_BETWEEN_COLONY_UNHAPPY = 7 * 24;
	private static final double HOURS_BETWEEN_NEAR_TANTRUM = 24;
	private static final int MEALS_PER_DAY = 2;
	// Food that rots is likely gone before the winter ends, so it counts for half
	private static final float PERISHABLE_FOOD_WORTH = 0.5f;

	private final MessageDispatcher messageDispatcher;
	private final SettlerTracker settlerTracker;
	private final SettlementItemTracker settlementItemTracker;
	private final I18nTranslator i18nTranslator;
	private GameContext gameContext;

	private double lastColonyUnhappyTime = -Double.MAX_VALUE;
	private final Map<Long, Double> lastNearTantrumTime = new HashMap<>();
	private int lastWinterWarningYear = -1;

	@Inject
	public SettlementAdvisor(MessageDispatcher messageDispatcher, SettlerTracker settlerTracker,
							 SettlementItemTracker settlementItemTracker, I18nTranslator i18nTranslator) {
		this.messageDispatcher = messageDispatcher;
		this.settlerTracker = settlerTracker;
		this.settlementItemTracker = settlementItemTracker;
		this.i18nTranslator = i18nTranslator;
		messageDispatcher.addListener(this, MessageType.HOUR_ELAPSED);
	}

	@Override
	public boolean handleMessage(Telegram msg) {
		if (msg.message == MessageType.HOUR_ELAPSED) {
			if (gameContext != null && !gameContext.getSettlementState().isGameOver()) {
				checkMood();
				checkWinterFood();
			}
			return false;
		}
		throw new IllegalArgumentException("Unexpected message type " + msg.message + " received by " + this.getClass().getSimpleName() + ", " + msg);
	}

	private void checkMood() {
		double now = gameContext.getGameClock().getCurrentGameTime();
		List<Entity> settlers = settlerTracker.getLiving().stream().toList();
		if (settlers.isEmpty()) {
			return;
		}

		int total = 0;
		int counted = 0;
		for (Entity settler : settlers) {
			HappinessComponent happiness = settler.getComponent(HappinessComponent.class);
			if (happiness == null) {
				continue;
			}
			int mood = happiness.getNetModifier();
			total += mood;
			counted++;

			if (mood <= NEAR_TANTRUM && now - lastNearTantrumTime.getOrDefault(settler.getId(), -Double.MAX_VALUE) >= HOURS_BETWEEN_NEAR_TANTRUM) {
				lastNearTantrumTime.put(settler.getId(), now);
				Notification notification = new Notification(NotificationType.SETTLER_NEAR_TANTRUM,
						settler.getLocationComponent().getWorldOrParentPosition().cpy(), new Selectable(settler, 0));
				notification.addTextReplacement("character", i18nTranslator.getDescription(settler));
				happiness.currentModifiers().stream()
						.filter(m -> m.modifierAmount < 0)
						.min(Comparator.comparingInt(m -> m.modifierAmount))
						.ifPresent(worst -> notification.addTextReplacement("reason", i18nTranslator.getWord(worst.getI18nKey())));
				messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION, notification);
			}
		}

		if (counted > 0 && total / counted <= COLONY_UNHAPPY_AVERAGE && now - lastColonyUnhappyTime >= HOURS_BETWEEN_COLONY_UNHAPPY) {
			lastColonyUnhappyTime = now;
			messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION, new Notification(NotificationType.COLONY_UNHAPPY, null, null));
		}
	}

	private void checkWinterFood() {
		GameClock clock = gameContext.getGameClock();
		// Ten days before winter, which is the first day of autumn (a season is ten days long)
		if (clock.getCurrentSeason() != Season.AUTUMN || clock.getDayOfSeason() != 1 || clock.getCurrentYear() == lastWinterWarningYear) {
			return;
		}
		lastWinterWarningYear = clock.getCurrentYear();

		float food = 0;
		for (Entity item : settlementItemTracker.getUnallocatedEdibleItems()) {
			int portions = item.getOrCreateComponent(ItemAllocationComponent.class).getNumUnallocated();
			food += item.getComponent(PerishableComponent.class) != null ? portions * PERISHABLE_FOOD_WORTH : portions;
		}
		int needed = settlerTracker.count() * MEALS_PER_DAY * clock.DAYS_IN_SEASON;
		if (food < needed) {
			messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION, new Notification(NotificationType.WINTER_FOOD_LOW, null, null));
		}
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {
		gameContext = null;
		lastColonyUnhappyTime = -Double.MAX_VALUE;
		lastNearTantrumTime.clear();
		lastWinterWarningYear = -1;
	}
}
