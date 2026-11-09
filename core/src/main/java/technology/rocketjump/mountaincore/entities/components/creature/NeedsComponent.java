package technology.rocketjump.mountaincore.entities.components.creature;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import technology.rocketjump.mountaincore.entities.ai.goap.CreatureCategory;
import technology.rocketjump.mountaincore.entities.ai.goap.EntityNeed;
import technology.rocketjump.mountaincore.entities.components.EntityComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.creature.CreatureEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.creature.status.Exhausted;
import technology.rocketjump.mountaincore.entities.model.physical.creature.status.VeryHungry;
import technology.rocketjump.mountaincore.entities.model.physical.creature.status.VeryThirsty;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.messaging.types.StatusMessage;
import technology.rocketjump.mountaincore.persistence.EnumParser;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;

import java.util.*;

import static technology.rocketjump.mountaincore.entities.model.physical.creature.Consciousness.AWAKE;

public class NeedsComponent implements EntityComponent {

	public static final double MAX_NEED_VALUE = 100;
	public static final double MIN_NEED_VALUE = -10;
	private Map<EntityNeed, Double> needValues = new EnumMap<>(EntityNeed.class);

	private static final double AWAKE_HOURS_UNTIL_UNCONSCIOUS = 30;
	// Invaders never sleep on a raid; they tire at half a dwarf's pace and retreat when spent (invasion retreat goal)
	private static final double INVADER_STAMINA_MULTIPLIER = 2;

	/**
	 * Where a creature sleeps decides how much a night gives back: a full night (MAX_SLEEP_HOURS in
	 * SleepOnFloorAction) in a bed restores everything, on a sleeping spot three quarters, on the floor half.
	 * A settler who sleeps on the floor wakes a little more tired every day.
	 */
	public enum SleepPlace {
		FLOOR(0.5), SLEEPING_SPOT(0.75), BED(1.0);

		public static final double FULL_NIGHT_HOURS = 8;
		final double restPerHour;

		SleepPlace(double shareOfFullNight) {
			this.restPerHour = shareOfFullNight * MAX_NEED_VALUE / FULL_NIGHT_HOURS;
		}
	}

	// Set every update by the sleeping action; anything else (knocked out, fainted) rests as on the floor
	private transient SleepPlace sleepPlace = SleepPlace.FLOOR;
	// 23 h: a settler eats twice a day, in the morning and the evening nourishment hours (was 46: once a day)
	private static final double HOURS_TO_STARVING_FROM_FULL = 23;
	private static final double HOURS_TO_DEHYDRATED_FROM_QUENCHED = 40;

	public NeedsComponent() {

	}

	public NeedsComponent(List<EntityNeed> needs, Random random) {
		for (EntityNeed entityNeed : needs) {
			// Start off with needs from 70 to
			double initialValue = 70 + (random.nextDouble() * 30);
			needValues.put(entityNeed, initialValue);
		}
	}

	private NeedsComponent(Map<EntityNeed, Double> other) {
		needValues.putAll(other);
	}

	@Override
	public EntityComponent clone(MessageDispatcher messageDispatcher, GameContext gameContext) {
		return new NeedsComponent(this.needValues);
	}

	public void update(double elapsedGameHours, Entity parentEntity, MessageDispatcher messageDispatcher) {
		CreatureEntityAttributes attributes = (CreatureEntityAttributes) parentEntity.getPhysicalEntityComponent().getAttributes();

		// Invaders come to raid and then leave: they never bed down in somebody else's settlement. They do tire,
		// slower than settlers, and a spent invader retreats (see the invasion retreat goal) - no endless berserkers
		boolean invader = CreatureCategory.INVADER.equals(CreatureCategory.getCategoryFor(parentEntity));

		if (needValues.containsKey(EntityNeed.SLEEP)) {
			Double currentSleepValue = needValues.get(EntityNeed.SLEEP);

			// TODO MODDING data-drive this
			if (AWAKE.equals(attributes.getConsciousness())) {
				double hoursUntilSpent = invader ? AWAKE_HOURS_UNTIL_UNCONSCIOUS * INVADER_STAMINA_MULTIPLIER : AWAKE_HOURS_UNTIL_UNCONSCIOUS;
				double decrementAmount = (elapsedGameHours / hoursUntilSpent) * MAX_NEED_VALUE;
				needValues.put(EntityNeed.SLEEP, Math.max(currentSleepValue - decrementAmount, MIN_NEED_VALUE));
			} else if (!invader) {
				double incrementAmount = elapsedGameHours * sleepPlace.restPerHour;
				needValues.put(EntityNeed.SLEEP, Math.min(currentSleepValue + incrementAmount, MAX_NEED_VALUE));
			}

			if (!invader && getValue(EntityNeed.SLEEP) <= 0) {
				messageDispatcher.dispatchMessage(MessageType.APPLY_STATUS, new StatusMessage(parentEntity, Exhausted.class, null, null));
			}
		}

		if (needValues.containsKey(EntityNeed.FOOD)) {
			updateNeed(elapsedGameHours, EntityNeed.FOOD, HOURS_TO_STARVING_FROM_FULL);

			if (getValue(EntityNeed.FOOD) <= 0) {
				messageDispatcher.dispatchMessage(MessageType.APPLY_STATUS, new StatusMessage(parentEntity, VeryHungry.class, null, null));
			}
		}

		if (needValues.containsKey(EntityNeed.DRINK)) {
			updateNeed(elapsedGameHours, EntityNeed.DRINK, HOURS_TO_DEHYDRATED_FROM_QUENCHED);

			if (getValue(EntityNeed.DRINK) <= 0) {
				messageDispatcher.dispatchMessage(MessageType.APPLY_STATUS, new StatusMessage(parentEntity, VeryThirsty.class, null, null));
			}
		}
	}

	private void updateNeed(double elapsedGameHours, EntityNeed need, double hoursToFullyEmpty) {
		Double currentValue = needValues.get(need);
		double changeAmount = (elapsedGameHours / hoursToFullyEmpty) * MAX_NEED_VALUE;
		Double newValue = Math.max(currentValue - changeAmount, MIN_NEED_VALUE);
		needValues.put(need, newValue);
	}

	public Double getValue(EntityNeed need) {
		return needValues.get(need);
	}

	public void setValue(EntityNeed need, double value) {
		needValues.put(need, Math.max(Math.min(value, MAX_NEED_VALUE), MIN_NEED_VALUE));
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
		if (!needValues.isEmpty()) {
			JSONArray needsJson = new JSONArray();
			for (Map.Entry<EntityNeed, Double> entry : needValues.entrySet()) {
				JSONObject needJson = new JSONObject(true);
				needJson.put("need", entry.getKey());
				needJson.put("value", entry.getValue());
				needsJson.add(needJson);
			}
			asJson.put("needs", needsJson);
		}
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		JSONArray needsJson = asJson.getJSONArray("needs");
		if (needsJson != null) {
			for (int cursor = 0; cursor < needsJson.size(); cursor++) {
				JSONObject needJson = needsJson.getJSONObject(cursor);
				this.needValues.put(
						EnumParser.getEnumValue(needJson, "need", EntityNeed.class, null),
						needJson.getDoubleValue("value")
				);
			}
		}
	}

	public void setSleepPlace(SleepPlace sleepPlace) {
		this.sleepPlace = sleepPlace == null ? SleepPlace.FLOOR : sleepPlace;
	}

	public boolean has(EntityNeed need) {
		return needValues.containsKey(need);
	}

	public Set<Map.Entry<EntityNeed, Double>> getAll() {
		return needValues.entrySet();
	}
}
