package technology.rocketjump.mountaincore.entities.ai.goap.actions;

import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.math.Vector2;
import technology.rocketjump.mountaincore.entities.ai.goap.AssignedGoal;
import technology.rocketjump.mountaincore.entities.ai.goap.CreatureCategory;
import technology.rocketjump.mountaincore.entities.ai.goap.EntityNeed;
import technology.rocketjump.mountaincore.entities.ai.goap.condition.GoalSelectionByBadlyWounded;
import technology.rocketjump.mountaincore.entities.ai.memory.Memory;
import technology.rocketjump.mountaincore.entities.ai.memory.MemoryType;
import technology.rocketjump.mountaincore.entities.components.creature.HappinessComponent;
import technology.rocketjump.mountaincore.entities.components.creature.MemoryComponent;
import technology.rocketjump.mountaincore.entities.components.creature.NeedsComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.creature.Consciousness;
import technology.rocketjump.mountaincore.entities.model.physical.creature.CreatureEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.creature.DeathReason;
import technology.rocketjump.mountaincore.entities.model.physical.creature.Race;
import technology.rocketjump.mountaincore.entities.model.physical.creature.body.BodyPart;
import technology.rocketjump.mountaincore.entities.model.physical.creature.body.BodyPartDamage;
import technology.rocketjump.mountaincore.entities.model.physical.creature.body.BodyPartDamageLevel;
import technology.rocketjump.mountaincore.entities.model.physical.creature.body.BodyPartOrgan;
import technology.rocketjump.mountaincore.entities.model.physical.creature.body.organs.OrganDamageLevel;
import technology.rocketjump.mountaincore.environment.model.WeatherType;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.messaging.types.CreatureDeathMessage;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import static technology.rocketjump.mountaincore.assets.entities.model.EntityAssetOrientation.*;
import static technology.rocketjump.mountaincore.entities.ai.goap.EntityNeed.DRINK;
import static technology.rocketjump.mountaincore.entities.ai.goap.EntityNeed.FOOD;
import static technology.rocketjump.mountaincore.entities.ai.goap.actions.Action.CompletionType.FAILURE;
import static technology.rocketjump.mountaincore.entities.ai.goap.actions.Action.CompletionType.SUCCESS;
import static technology.rocketjump.mountaincore.entities.components.creature.HappinessComponent.HappinessModifier.SLEPT_ON_GROUND;
import static technology.rocketjump.mountaincore.entities.components.creature.HappinessComponent.HappinessModifier.SLEPT_OUTSIDE;
import static technology.rocketjump.mountaincore.entities.components.creature.NeedsComponent.MAX_NEED_VALUE;
import static technology.rocketjump.mountaincore.mapping.tile.MapTile.NULL_TILE;
import static technology.rocketjump.mountaincore.mapping.tile.roof.TileRoofState.OPEN;

public class SleepOnFloorAction extends Action {
	// Chances per restful sleep in a bed; sleeping on the floor heals at floorHealingFactor() of these
	private static final float CHANCE_TO_HEAL_ORGAN_DAMAGE = 0.1f;
	private static final float CHANCE_TO_HEAL_BODY_DAMAGE = 0.4f;
	private static final float FLOOR_HEALING_FACTOR = 0.5f;
	// A badly wounded sleeper stays in bed once rested, healing again every this many hours (bed rest)
	private static final double HOURS_BETWEEN_BED_REST_HEALING = 4.0;
	private transient double lastBedRestHealingGameTime = -1;
	// A night's sleep ends after this long, slept out or not: where it was slept decides how rested it leaves
	// the sleeper (NeedsComponent.SleepPlace). Saved, so loading a game does not restart the night.
	public static final double MAX_SLEEP_HOURS = NeedsComponent.SleepPlace.FULL_NIGHT_HOURS;
	private double sleepStartedAt = -1;

	public SleepOnFloorAction(AssignedGoal parent) {
		super(parent);
	}

	@Override
	public void update(float deltaTime, GameContext gameContext) {
		if (wakeIfInvader(gameContext)) {
			return;
		}
		CreatureEntityAttributes attributes = (CreatureEntityAttributes) parent.parentEntity.getPhysicalEntityComponent().getAttributes();
		if (!Consciousness.SLEEPING.equals(attributes.getConsciousness())) {
			changeToSleeping(gameContext);
		}
		setSleepPlace(NeedsComponent.SleepPlace.FLOOR);

		HappinessComponent happinessComponent = parent.parentEntity.getComponent(HappinessComponent.class);
		if (happinessComponent != null) {
			happinessComponent.add(SLEPT_ON_GROUND);
		}

		checkForWakingUp(gameContext);
	}

	@Override
	public String getDescriptionOverrideI18nKey() {
		return "ACTION.SLEEP_ON_FLOOR.DESCRIPTION";
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
		if (sleepStartedAt >= 0) {
			asJson.put("sleepStartedAt", sleepStartedAt);
		}
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		Double saved = asJson.getDouble("sleepStartedAt");
		sleepStartedAt = saved == null ? -1 : saved;
	}

	/**
	 * Invaders do not sleep on a raid. One that lay down anyway (bed rest used to take wounded invaders too) slept
	 * for good: a sleeper wakes when rested, and an invader's rest never grows. Up it gets.
	 */
	protected boolean wakeIfInvader(GameContext gameContext) {
		if (!CreatureCategory.INVADER.equals(CreatureCategory.getCategoryFor(parent.parentEntity))) {
			return false;
		}
		if (isAsleep()) {
			changeToAwake(gameContext);
		}
		completionType = SUCCESS;
		return true;
	}

	protected void setSleepPlace(NeedsComponent.SleepPlace sleepPlace) {
		NeedsComponent needsComponent = parent.parentEntity.getComponent(NeedsComponent.class);
		if (needsComponent != null) {
			needsComponent.setSleepPlace(sleepPlace);
		}
	}

	protected void checkForWakingUp(GameContext gameContext) {
		MapTile currentTile = gameContext.getAreaMap().getTile(parent.parentEntity.getLocationComponent().getWorldOrParentPosition());
		if (currentTile == null) {
			currentTile = NULL_TILE;
		}
		HappinessComponent happinessComponent = parent.parentEntity.getComponent(HappinessComponent.class);
		if (OPEN.equals(currentTile.getRoof().getState()) && happinessComponent != null) {
			happinessComponent.add(SLEPT_OUTSIDE);

			if (gameContext.getMapEnvironment().getCurrentWeather().getHappinessModifiers().containsKey(WeatherType.HappinessInteraction.SLEEPING)) {
				happinessComponent.add(gameContext.getMapEnvironment().getCurrentWeather().getHappinessModifiers().get(WeatherType.HappinessInteraction.SLEEPING));
			}
		}

		NeedsComponent needsComponent = parent.parentEntity.getComponent(NeedsComponent.class);
		double now = gameContext.getGameClock().getCurrentGameTime();
		if (sleepStartedAt < 0 || sleepStartedAt > now) {
			sleepStartedAt = now;
		}
		// All changes in need amounts are handled by SettlerBehaviour
		boolean nightIsOver = needsComponent.getValue(EntityNeed.SLEEP) >= 100.0 || now - sleepStartedAt >= MAX_SLEEP_HOURS;
		if (nightIsOver && stayOnBedRest(needsComponent, gameContext)) {
			return;
		}
		if (nightIsOver) {
			if (happinessComponent != null) {
				// Only entities with tracked happiness can freeze outside

				if (OPEN.equals(currentTile.getRoof().getState()) && gameContext.getMapEnvironment().getCurrentWeather().getChanceToFreezeToDeathFromSleeping() != null) {
					float roll = gameContext.getRandom().nextFloat();
					if (roll < gameContext.getMapEnvironment().getCurrentWeather().getChanceToFreezeToDeathFromSleeping()) {
						parent.messageDispatcher.dispatchMessage(MessageType.CREATURE_DEATH, new CreatureDeathMessage(parent.parentEntity, DeathReason.FROZEN, null));
						completionType = FAILURE;
						return;
					}
				}
			}

			changeToAwake(gameContext);
			completionType = SUCCESS;
		} else if ((needsComponent.has(FOOD) && needsComponent.getValue(FOOD) <= 0.0)
				|| (needsComponent.has(DRINK) && needsComponent.getValue(DRINK) <= 0.0)) {
			// Currently starving or dehydrated so wake up after sleep at 30%
			if (needsComponent.getValue(EntityNeed.SLEEP) >= 30.0) {
				changeToAwake(gameContext);
				completionType = SUCCESS;
			}
		} else if (parent.parentEntity.isOnFire()) {
			changeToAwake(gameContext);
			completionType = SUCCESS;
		}
	}

	@Override
	public void actionInterrupted(GameContext gameContext) {
		if (isAsleep()) {
			changeToAwake(gameContext);
		}
		super.actionInterrupted(gameContext);
	}

	protected boolean isAsleep() {
		CreatureEntityAttributes attributes = (CreatureEntityAttributes) parent.parentEntity.getPhysicalEntityComponent().getAttributes();
		return attributes.getConsciousness().equals(Consciousness.SLEEPING);
	}

	protected void changeToSleeping(GameContext gameContext) {
		changeToConsciousnessOnFloor(parent.parentEntity, Consciousness.SLEEPING, gameContext, parent.messageDispatcher);
	}

	public static void changeToConsciousnessOnFloor(Entity entity, Consciousness consciousness,
													GameContext gameContext, MessageDispatcher messageDispatcher) {
		entity.getLocationComponent().setLinearVelocity(Vector2.Zero);
		entity.getBehaviourComponent().getSteeringComponent().destinationReached();

		Race race = ((CreatureEntityAttributes) entity.getPhysicalEntityComponent().getAttributes()).getRace();
		if (race.getBehaviour().getIsSapient()) {
			// face in a direction and rotate as appropriate
			showAsRotatedOnSide(entity, gameContext);
		}
		CreatureEntityAttributes attributes = (CreatureEntityAttributes) entity.getPhysicalEntityComponent().getAttributes();
		attributes.setConsciousness(consciousness);
		if (entity.isJobAssignable()) {
			messageDispatcher.dispatchMessage(MessageType.SETTLER_FELL_ASLEEP);
		}
		messageDispatcher.dispatchMessage(MessageType.ENTITY_ASSET_UPDATE_REQUIRED, entity);
	}

	public static void showAsRotatedOnSide(Entity entity, GameContext gameContext) {
		if (gameContext.getRandom().nextBoolean()) {
			entity.getLocationComponent().setRotation(80f + (gameContext.getRandom().nextFloat() * 20f));
			if (gameContext.getRandom().nextBoolean()) {
				entity.getLocationComponent().setFacing(DOWN_LEFT.toVector2());
			} else {
				entity.getLocationComponent().setFacing(DOWN.toVector2());
			}

		} else {
			entity.getLocationComponent().setRotation(260f + (gameContext.getRandom().nextFloat() * 20f));
			if (gameContext.getRandom().nextBoolean()) {
				entity.getLocationComponent().setFacing(DOWN_RIGHT.toVector2());
			} else {
				entity.getLocationComponent().setFacing(DOWN.toVector2());
			}
		}
	}

	protected void changeToAwake(GameContext gameContext) {
		CreatureEntityAttributes attributes = (CreatureEntityAttributes) parent.parentEntity.getPhysicalEntityComponent().getAttributes();

		// Stop rotation
		parent.parentEntity.getLocationComponent().setRotation(0);

		attributes.setConsciousness(Consciousness.AWAKE);
		setSleepPlace(NeedsComponent.SleepPlace.FLOOR);
		sleepStartedAt = -1;
		MemoryComponent memoryComponent = parent.parentEntity.getComponent(MemoryComponent.class);
		if (memoryComponent != null) {
			memoryComponent.addShortTerm(new Memory(MemoryType.WOKE_UP, gameContext.getGameClock()), gameContext.getGameClock());
		}
		if (parent.parentEntity.isSettler()) {
			parent.messageDispatcher.dispatchMessage(MessageType.SETTLER_WOKE_UP);
		}
		parent.messageDispatcher.dispatchMessage(MessageType.ENTITY_ASSET_UPDATE_REQUIRED, parent.parentEntity);

		// Heal damage if this was a restful sleep
		Double sleepNeed = parent.parentEntity.getComponent(NeedsComponent.class).getValue(EntityNeed.SLEEP);
		if (sleepNeed > MAX_NEED_VALUE / 2) {
			healOnce(attributes, gameContext);
		}
	}

	/**
	 * Bed rest: a sleeper with a bleeding wound or broken bones does not get up once rested, but keeps lying
	 * there and heals again every few hours, until those wounds are better. Hunger, thirst (the goal is
	 * interrupted by low needs) and fire still get it up.
	 */
	private boolean stayOnBedRest(NeedsComponent needsComponent, GameContext gameContext) {
		if (!GoalSelectionByBadlyWounded.needsBedRest(parent.parentEntity) || parent.parentEntity.isOnFire()
				|| (needsComponent.has(FOOD) && needsComponent.getValue(FOOD) <= 0.0)
				|| (needsComponent.has(DRINK) && needsComponent.getValue(DRINK) <= 0.0)) {
			return false;
		}
		double now = gameContext.getGameClock().getCurrentGameTime();
		if (lastBedRestHealingGameTime < 0) {
			lastBedRestHealingGameTime = now;
		} else if (now - lastBedRestHealingGameTime >= HOURS_BETWEEN_BED_REST_HEALING) {
			lastBedRestHealingGameTime = now;
			healOnce((CreatureEntityAttributes) parent.parentEntity.getPhysicalEntityComponent().getAttributes(), gameContext);
		}
		return true;
	}

	/** How well sleeping here heals, against a bed's 1.0: the floor heals at half the rate. */
	protected float healingFactor(GameContext gameContext) {
		return FLOOR_HEALING_FACTOR;
	}

	private void healOnce(CreatureEntityAttributes attributes, GameContext gameContext) {
		float factor = healingFactor(gameContext);
		{
			for (Map.Entry<BodyPart, BodyPartDamage> entry : new ArrayList<>(attributes.getBody().getAllDamage())) {
				if (entry.getValue().getDamageLevel().equals(BodyPartDamageLevel.Destroyed)) {
					continue;
				}

				Iterator<Map.Entry<BodyPartOrgan, OrganDamageLevel>> organDamageIterator = entry.getValue().getOrganDamage().entrySet().iterator();
				while (organDamageIterator.hasNext()) {
					Map.Entry<BodyPartOrgan, OrganDamageLevel> organDamageLevelEntry = organDamageIterator.next();
					if (!organDamageLevelEntry.getValue().equals(OrganDamageLevel.DESTROYED)) {
						if (gameContext.getRandom().nextFloat() < CHANCE_TO_HEAL_ORGAN_DAMAGE * factor) {
							organDamageIterator.remove();
						}
					}
				}

				if (!entry.getValue().getDamageLevel().equals(BodyPartDamageLevel.None)) {
					if (gameContext.getRandom().nextFloat() < CHANCE_TO_HEAL_BODY_DAMAGE * factor) {
						entry.getValue().healOneLevel();
					}
				}

				if (entry.getValue().getDamageLevel().equals(BodyPartDamageLevel.None) && entry.getValue().getOrganDamage().isEmpty()) {
					attributes.getBody().clearDamage(entry.getKey());
				}

			}
		}
	}
}
