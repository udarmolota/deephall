package technology.rocketjump.mountaincore.settlement.trading;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.badlogic.gdx.math.Vector2;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.environment.GameClock;
import technology.rocketjump.mountaincore.environment.model.Season;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.GameContextAware;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.settlement.MapEntry;
import technology.rocketjump.mountaincore.settlement.SettlerTracker;
import technology.rocketjump.mountaincore.settlement.notifications.Notification;
import technology.rocketjump.mountaincore.settlement.notifications.NotificationType;
import technology.rocketjump.mountaincore.settlement.trading.model.TraderInfo;

import static technology.rocketjump.mountaincore.messaging.MessageType.*;

@Singleton
public class TradingMessageHandler implements Telegraph, GameContextAware {

	// A caravan with no way in waits this long at the map edge before it gives up
	private static final int DAYS_CARAVAN_WAITS_AT_EDGE = 3;

	private final MessageDispatcher messageDispatcher;
	private final SettlerTracker settlerTracker;
	private final TradeCaravanGenerator tradeCaravanGenerator;
	private GameContext gameContext;

	@Inject
	public TradingMessageHandler(MessageDispatcher messageDispatcher, SettlerTracker settlerTracker, TradeCaravanGenerator tradeCaravanGenerator) {
		this.messageDispatcher = messageDispatcher;
		this.settlerTracker = settlerTracker;
		this.tradeCaravanGenerator = tradeCaravanGenerator;

		messageDispatcher.addListener(this, HOUR_ELAPSED);
		messageDispatcher.addListener(this, MessageType.DAY_ELAPSED);
		messageDispatcher.addListener(this, MessageType.TRIGGER_TRADE_CARAVAN);
	}

	@Override
	public boolean handleMessage(Telegram msg) {
		switch (msg.message) {
			case HOUR_ELAPSED -> {
				onHourElapsed();
				return false;
			}
			case DAY_ELAPSED -> {
				onDayElapsed();
				return false;
			}
			case TRIGGER_TRADE_CARAVAN -> {
				triggerTradeCaravan();
				return true;
			}
			default ->
					throw new IllegalArgumentException("Unexpected message type " + msg.message + " received by " + this.getClass().getSimpleName() + ", " + msg);
		}
	}

	private void onHourElapsed() {
		TraderInfo traderInfo = gameContext.getSettlementState().getTraderInfo();
		if (traderInfo.getHoursCaravanWaitsAtEdge() != null) {
			updateCaravanWaitingAtEdge(traderInfo);
		}
		Double hoursUntilTraderArrives = traderInfo.getHoursUntilTraderArrives();
		if (hoursUntilTraderArrives != null) {
			hoursUntilTraderArrives -= 1.0;
			if (hoursUntilTraderArrives <= 0) {
				messageDispatcher.dispatchMessage(MessageType.TRIGGER_TRADE_CARAVAN);
				traderInfo.setHoursUntilTraderArrives(null);
				setupNextTraderArrival();
			} else {
				traderInfo.setHoursUntilTraderArrives(hoursUntilTraderArrives);
			}
		}
	}

	private void onDayElapsed() {
		TraderInfo traderInfo = gameContext.getSettlementState().getTraderInfo();
		if (traderInfo.getNextVisitDayOfYear() == null) {
			setupNextTraderArrival();
		} else if (traderInfo.getNextVisitDayOfYear() == gameContext.getGameClock().getDayOfYear()) {
			pickNextTraderArrivalTime();
			traderInfo.setNextVisitDayOfYear(null);
		}
	}

	private void setupNextTraderArrival() {
		GameClock clock = gameContext.getGameClock();
		Season currentSeason = clock.getCurrentSeason();
		Season nextSeason = currentSeason.getNext();
		if (nextSeason.equals(Season.WINTER)) {
			nextSeason = Season.SPRING;
		}

		int targetDayOfYear = 1;
		for (Season seasonCounter = Season.SPRING; seasonCounter != nextSeason; seasonCounter = seasonCounter.getNext()) {
			targetDayOfYear += clock.DAYS_IN_SEASON;
		}
		targetDayOfYear += gameContext.getRandom().nextInt(1, Math.max((clock.DAYS_IN_SEASON / 2), 2) + 1);
		gameContext.getSettlementState().getTraderInfo().setNextVisitDayOfYear(targetDayOfYear);
	}

	private void pickNextTraderArrivalTime() {
		gameContext.getSettlementState().getTraderInfo().setHoursUntilTraderArrives(
				gameContext.getRandom().nextDouble(
						0.25 * gameContext.getGameClock().HOURS_IN_DAY,
						0.4 * gameContext.getGameClock().HOURS_IN_DAY
				)
		);
	}

	private void triggerTradeCaravan() {
		if (settlerTracker.getLiving().isEmpty()) {
			Logger.warn("No settlers left for traders to visit");
			return;
		}
		TraderInfo traderInfo = gameContext.getSettlementState().getTraderInfo();
		Vector2 tradeSpawnLocation = MapEntry.findEntry(gameContext, traderInfo.getTradeRouteEntry());
		if (tradeSpawnLocation == null) {
			// No way in: the caravan waits at the edge and the player is told where the way is cut off
			if (traderInfo.getHoursCaravanWaitsAtEdge() == null) {
				traderInfo.setHoursCaravanWaitsAtEdge((double) DAYS_CARAVAN_WAITS_AT_EDGE * gameContext.getGameClock().HOURS_IN_DAY);
				messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION,
						new Notification(NotificationType.CARAVAN_BLOCKED, MapEntry.findBlockage(gameContext), null));
			}
			return;
		}

		traderInfo.setHoursCaravanWaitsAtEdge(null);
		traderInfo.setTradeRouteEntry(tradeSpawnLocation);
		tradeCaravanGenerator.generateTradeCaravan(tradeSpawnLocation, traderInfo);
	}

	private void updateCaravanWaitingAtEdge(TraderInfo traderInfo) {
		if (MapEntry.findEntry(gameContext, traderInfo.getTradeRouteEntry()) != null) {
			triggerTradeCaravan();
			return;
		}
		double hoursLeft = traderInfo.getHoursCaravanWaitsAtEdge() - 1.0;
		if (hoursLeft <= 0) {
			traderInfo.setHoursCaravanWaitsAtEdge(null);
			messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION, new Notification(NotificationType.CARAVAN_LEFT, null, null));
		} else {
			traderInfo.setHoursCaravanWaitsAtEdge(hoursLeft);
		}
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {

	}

}
