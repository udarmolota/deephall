package technology.rocketjump.mountaincore.settlement;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.Vector2;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.entities.ai.goap.AssignedGoal;
import technology.rocketjump.mountaincore.entities.ai.goap.Goal;
import technology.rocketjump.mountaincore.entities.ai.goap.SpecialGoal;
import technology.rocketjump.mountaincore.entities.ai.goap.actions.location.GoToLocationAction;
import technology.rocketjump.mountaincore.entities.behaviour.creature.CreatureBehaviour;
import technology.rocketjump.mountaincore.entities.components.Faction;
import technology.rocketjump.mountaincore.entities.components.FactionComponent;
import technology.rocketjump.mountaincore.entities.components.ItemAllocationComponent;
import technology.rocketjump.mountaincore.entities.components.creature.SkillsComponent;
import technology.rocketjump.mountaincore.entities.factories.SettlerFactory;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.EntityType;
import technology.rocketjump.mountaincore.environment.GameClock;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.Updatable;
import technology.rocketjump.mountaincore.jobs.SkillDictionary;
import technology.rocketjump.mountaincore.mapping.factories.CreaturePopulator;
import technology.rocketjump.mountaincore.mapping.model.TiledMap;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.mapping.tile.roof.TileRoofState;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.misc.VectorUtils;
import technology.rocketjump.mountaincore.settlement.notifications.Notification;
import technology.rocketjump.mountaincore.settlement.notifications.NotificationType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Singleton
public class ImmigrationManager implements Updatable, Telegraph {

	private final MessageDispatcher messageDispatcher;
	private final SettlementItemTracker settlementItemTracker;
	private final SettlerTracker settlerTracker;
	private final SettlerFactory settlerFactory;
	private final SkillDictionary skillDictionary;
	private final CreaturePopulator creaturePopulator;
	// Newcomers arrive one at a time, every few days, rather than as one group a year
	private final boolean arrivalsEnabled;
	private final int minDaysBetweenArrivals;
	private final int maxDaysBetweenArrivals;
	private final boolean extraFoodImmigrationEnabled;
	private final int minDaysBetweenArrivalsWithExtraFood;
	private GameContext gameContext;

	private float timeSinceLastUpdate;
	// Not saved: after loading a game the player is simply told again
	private boolean playerToldArrivalIsBlocked;

	@Inject
	public ImmigrationManager(MessageDispatcher messageDispatcher, SettlementItemTracker settlementItemTracker, SettlerTracker settlerTracker,
							  SettlerFactory settlerFactory, SkillDictionary skillDictionary, CreaturePopulator creaturePopulator) {
		this.creaturePopulator = creaturePopulator;
		FileHandle settingsJsonFile = new FileHandle("assets/settings/immigrationSettings.json");
		JSONObject immigrationSettings = JSON.parseObject(settingsJsonFile.readString());

		JSONObject arrivals = immigrationSettings.getJSONObject("arrivals");
		arrivalsEnabled = arrivals.getBooleanValue("enabled");
		minDaysBetweenArrivals = arrivals.getIntValue("minDaysBetweenArrivals");
		maxDaysBetweenArrivals = arrivals.getIntValue("maxDaysBetweenArrivals");

		JSONObject extraFood = immigrationSettings.getJSONObject("extraFoodImmigration");
		extraFoodImmigrationEnabled = extraFood.getBooleanValue("enabled");
		minDaysBetweenArrivalsWithExtraFood = extraFood.getIntValue("minDaysBetweenArrivals");

		this.messageDispatcher = messageDispatcher;
		this.settlementItemTracker = settlementItemTracker;
		this.settlerTracker = settlerTracker;
		this.settlerFactory = settlerFactory;
		this.skillDictionary = skillDictionary;

		messageDispatcher.addListener(this, MessageType.YEAR_ELAPSED);
	}

	@Override
	public boolean handleMessage(Telegram msg) {
		switch (msg.message) {
			case MessageType.YEAR_ELAPSED: {
				creaturePopulator.addAnimalsAtEdge(gameContext);
				return true;
			}
			default:
				throw new IllegalArgumentException("Unexpected message type " + msg.message + " received by " + this.toString() + ", " + msg.toString());
		}
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {

	}

	@Override
	public void update(float deltaTime) {
		timeSinceLastUpdate += deltaTime;
		if (timeSinceLastUpdate > 1.44f) {
			timeSinceLastUpdate = 0f;

			if (gameContext != null && arrivalsEnabled && gameContext.getSettlementState().getNextImmigrationGameTime() == null) {
				gameContext.getSettlementState().setNextImmigrationGameTime(pickNextImmigrationTime());
			}

			if (gameContext != null && gameContext.getSettlementState().getNextImmigrationGameTime() != null &&
					gameContext.getSettlementState().getNextImmigrationGameTime() < gameContext.getGameClock().getCurrentGameTime() &&
					!gameContext.getSettlementState().isGameOver()) {
				triggerImmigration();
			}

			if (gameContext != null && gameContext.getSettlementState().getImmigrantCounter() > 0 && gameContext.getSettlementState().getImmigrationPoint() != null) {
				createImmigrant(gameContext.getSettlementState().getImmigrationPoint());
				gameContext.getSettlementState().setImmigrantCounter(gameContext.getSettlementState().getImmigrantCounter() - 1);
			}

		}
	}

	@Override
	public boolean runWhilePaused() {
		return false;
	}

	/**
	 * The next newcomer is due some days from now, during the working day (09:00 to 17:00).
	 * A surplus of food brings them sooner.
	 */
	private Double pickNextImmigrationTime() {
		GameClock clock = gameContext.getGameClock();
		int days = minDaysBetweenArrivals + gameContext.getRandom().nextInt(maxDaysBetweenArrivals - minDaysBetweenArrivals + 1);
		if (extraFoodImmigrationEnabled) {
			days = Math.max(minDaysBetweenArrivalsWithExtraFood, days - numSurplusSettlersFed());
		}

		double arrivalTimeOfDay = 9.0 + (gameContext.getRandom().nextDouble() * 8.0);
		double hoursFromNow = (clock.HOURS_IN_DAY * days) + (arrivalTimeOfDay - clock.getGameTimeInHours());
		return clock.getCurrentGameTime() + hoursFromNow;
	}

	/**
	 * How many more settlers the spare food could feed. Each settler is meant to have food for
	 * about three seasons: 2 meals a day, and one edible item makes about 4 meals.
	 */
	private int numSurplusSettlersFed() {
		int foodAmount = 0;
		for (Entity entity : settlementItemTracker.getUnallocatedEdibleItems()) {
			foodAmount += entity.getOrCreateComponent(ItemAllocationComponent.class).getNumUnallocated();
		}

		int foodNeededPerSettler = (gameContext.getGameClock().DAYS_IN_SEASON * 3 * 2) / 4;
		int surplusFood = foodAmount - (settlerTracker.count() * foodNeededPerSettler);
		return Math.max(0, surplusFood / foodNeededPerSettler);
	}

	public void triggerImmigration() {
		SettlementState settlementState = gameContext.getSettlementState();
		// Left over from saves made when a whole year's group arrived at once
		settlementState.setImmigrantsDue(0);

		if (!settlementState.isAllowImmigration()) {
			// The player turned immigration off: this newcomer stays away, the next one is scheduled
			settlementState.setNextImmigrationGameTime(null);
			return;
		}

		Vector2 immigrationPoint = pickImmigrationPoint();
		if (immigrationPoint == null) {
			// Nobody is lost: the same newcomer tries again tomorrow
			settlementState.setNextImmigrationGameTime(gameContext.getGameClock().getCurrentGameTime() + gameContext.getGameClock().HOURS_IN_DAY);
			if (!playerToldArrivalIsBlocked) {
				Logger.warn("Could not find valid map edge to spawn immigration from");
				messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION,
						new Notification(NotificationType.IMMIGRANT_BLOCKED, MapEntry.findBlockage(gameContext), null));
				playerToldArrivalIsBlocked = true;
			}
			return;
		}

		playerToldArrivalIsBlocked = false;
		settlementState.setNextImmigrationGameTime(null);
		settlementState.setImmigrantCounter(1);
		settlementState.setImmigrationPoint(immigrationPoint);
		messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION, new Notification(NotificationType.IMMIGRANTS_ARRIVED, immigrationPoint, null));
	}

	private Vector2 pickImmigrationPoint() {
		// Must be map edge in region where settlers currently are

		TiledMap areaMap = gameContext.getAreaMap();
		int regionId = determineSettlementRegionId(gameContext, true);

		List<MapTile> potentialImmigrationPoints = new ArrayList<>();
		for (int x = 0; x < areaMap.getWidth(); x++) {
			MapTile bottomEdgeTile = areaMap.getTile(x, 0);
			if (bottomEdgeTile.getRegionId() == regionId && bottomEdgeTile.getRoof().getState().equals(TileRoofState.OPEN)) {
				potentialImmigrationPoints.add(bottomEdgeTile);
			}

			MapTile topEdgeTile = areaMap.getTile(x, areaMap.getHeight() - 1);
			if (topEdgeTile.getRegionId() == regionId && topEdgeTile.getRoof().getState().equals(TileRoofState.OPEN)) {
				potentialImmigrationPoints.add(topEdgeTile);
			}
		}
		for (int y = 1; y < areaMap.getHeight() - 1; y++) {
			MapTile leftEdgeTile = areaMap.getTile(0, y);
			if (leftEdgeTile.getRegionId() == regionId && leftEdgeTile.getRoof().getState().equals(TileRoofState.OPEN)) {
				potentialImmigrationPoints.add(leftEdgeTile);
			}
			MapTile rightEdgeTile = areaMap.getTile(areaMap.getWidth() -1, y);
			if (rightEdgeTile.getRegionId() ==  regionId && rightEdgeTile.getRoof().getState().equals(TileRoofState.OPEN)) {
				potentialImmigrationPoints.add(rightEdgeTile);
			}
		}
		if (potentialImmigrationPoints.isEmpty()) {
			return null;
		}

		MapTile immigrationTile = potentialImmigrationPoints.get(gameContext.getRandom().nextInt(potentialImmigrationPoints.size()));
		GridPoint2 tilePosition = immigrationTile.getTilePosition();
		boolean leftEdge = tilePosition.x == 0;
		boolean rightEdge = tilePosition.x == areaMap.getWidth() - 1;
		boolean topEdge = tilePosition.y == areaMap.getHeight() - 1;
		boolean bottomEdge = tilePosition.y == 0;

		Vector2 immigrationPoint = immigrationTile.getWorldPositionOfCenter();
		if (leftEdge) {
			immigrationPoint.add(-0.49f, 0f);
		} else if (rightEdge) {
			immigrationPoint.add(0.49f, 0f);
		}
		if (topEdge) {
			immigrationPoint.add(0f, 0.49f);
		} else if (bottomEdge) {
			immigrationPoint.add(0f, -0.49f);
		}
		return immigrationPoint;
	}

	public static int determineSettlementRegionId(GameContext gameContext, boolean settlersOnly) {
		TiledMap areaMap = gameContext.getAreaMap();
		final Predicate<Entity> predicate;
		if (settlersOnly) {
			predicate = Entity::isSettler;
		} else {
			predicate = entity -> entity.isSettler() ||
					(entity.getType() == EntityType.ITEM && Faction.SETTLEMENT == entity.getOrCreateComponent(FactionComponent.class).getFaction());
		}

		Map<Integer, Long> regionIdFrequency = gameContext.getEntities().values().stream()
				.filter(predicate)
				.map(settler -> settler.getLocationComponent().getWorldPosition())
				.map(areaMap::getTile)
				.filter(Objects::nonNull)
				.map(MapTile::getRegionId)
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

		return regionIdFrequency.entrySet()
				.stream()
				.max(Map.Entry.comparingByValue())
				.map(Map.Entry::getKey).orElse(gameContext.getAreaMap().getTile(gameContext.getAreaMap().getEmbarkPoint()).getRegionId());
	}

	private void createImmigrant(Vector2 spawnPosition) {
		Entity settler = settlerFactory.create(spawnPosition, new SkillsComponent().withNullProfessionActive(), gameContext, true);
		CreatureBehaviour settlerBehaviour = (CreatureBehaviour) settler.getBehaviourComponent();

		Goal idleGoal = SpecialGoal.IDLE.getInstance();
		AssignedGoal assignedGoal = new AssignedGoal(idleGoal, settler, messageDispatcher, gameContext);
		assignedGoal.actionQueue.pop();
		GoToLocationAction goToLocationAction = new GoToLocationAction(assignedGoal);
		goToLocationAction.setOverrideLocation(VectorUtils.toVector(gameContext.getAreaMap().getEmbarkPoint()));
		assignedGoal.actionQueue.push(goToLocationAction);
		settlerBehaviour.setCurrentGoal(assignedGoal);
	}

}
