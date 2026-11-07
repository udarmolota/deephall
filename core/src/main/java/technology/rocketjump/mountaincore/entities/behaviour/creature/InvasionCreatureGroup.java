package technology.rocketjump.mountaincore.entities.behaviour.creature;

import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import technology.rocketjump.mountaincore.entities.ai.goap.SpecialGoal;
import technology.rocketjump.mountaincore.entities.ai.goap.actions.invasion.CreateCampfireAction;
import technology.rocketjump.mountaincore.entities.ai.goap.actions.invasion.VictoryPointsForStealingAction;
import technology.rocketjump.mountaincore.entities.components.Faction;
import technology.rocketjump.mountaincore.entities.components.FactionComponent;
import technology.rocketjump.mountaincore.entities.components.creature.CombatStateComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.furniture.FurnitureEntityAttributes;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.invasions.model.InvasionDefinition;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.persistence.EnumParser;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;

public class InvasionCreatureGroup extends CreatureGroup {

	private static final int POINTS_LOST_ON_MEMBER_DEATH = 80;

	private InvasionDefinition invasionDefinition;
	private InvasionStage invasionStage = InvasionStage.ARRIVING;
	private double hoursInCurrentStage;
	private int victoryPointsEarned; // Used to track progress towards the goals of the invasion - killing settlers and stealing goods
	private int victoryPointsTarget;

	private SpecialGoal pendingSpecialGoal;

	@Override
	public void infrequentUpdate(GameContext gameContext, MessageDispatcher messageDispatcher) {
		double now = gameContext.getGameClock().getCurrentGameTime();
		if (lastUpdateGameTime == 0) {
			lastUpdateGameTime = now;
		}
		double elapsed = now - lastUpdateGameTime;
		lastUpdateGameTime = now;
		hoursInCurrentStage += elapsed;

		strongestMemberCount = Math.max(strongestMemberCount, memberEntityIds.size());
		if ((invasionStage.equals(InvasionStage.ARRIVING) || invasionStage.equals(InvasionStage.PREPARING))
				&& (memberLostBeforeRaid || anyMemberInCombat(gameContext))) {
			// Attacked in their camp: the preparation is over. A group that has lost more than half of its
			// fighters runs, the rest raid at once instead of standing about until their time is up
			memberLostBeforeRaid = false;
			if (invasionStage.equals(InvasionStage.PREPARING)) {
				removeCampfire(gameContext, messageDispatcher);
			}
			this.pendingSpecialGoal = null;
			if (isBeaten()) {
				retreatNow(gameContext);
			} else {
				this.invasionStage = InvasionStage.RAIDING;
				this.hoursInCurrentStage = 0;
			}
		}

		if (invasionStage.equals(InvasionStage.RAIDING) && isBeaten()) {
			// Morale breaks in the raid too: with more than half of the group fallen the rest run for it
			retreatNow(gameContext);
		}

		if (invasionStage.equals(InvasionStage.RAIDING)) {
			// Keeps the settlement's soldiers on duty past the end of their shift (CreatureBehaviour#getCurrentSchedule)
			gameContext.getSettlementState().reportRaiding(now);
		}

		if (invasionStage.equals(InvasionStage.RAIDING) && victoryPointsEarned >= victoryPointsTarget) {
			this.invasionStage = InvasionStage.RETREATING;
			this.hoursInCurrentStage = 0;
		}

		if (hoursInCurrentStage > invasionStage.durationHours) {
			switch (invasionStage) {
				case ARRIVING -> {
					this.pendingSpecialGoal = SpecialGoal.CREATE_CAMPFIRE;
					this.invasionStage = InvasionStage.PREPARING;
					this.hoursInCurrentStage = 0;
				}
				case PREPARING -> {
					removeCampfire(gameContext, messageDispatcher);
					this.invasionStage = InvasionStage.RAIDING;
					this.hoursInCurrentStage = 0;
				}
				case RAIDING -> {
					this.invasionStage = InvasionStage.RETREATING;
					this.hoursInCurrentStage = 0;
				}
				case RETREATING -> {
					// Do nothing
				}
			}
		}
	}

	public int getVictoryPointsTarget() {
		return victoryPointsTarget;
	}

	public void setVictoryPointsTarget(int victoryPointsTarget) {
		this.victoryPointsTarget = victoryPointsTarget;
	}

	public InvasionDefinition getInvasionDefinition() {
		return invasionDefinition;
	}

	public void addVictoryPoints(int points) {
		this.victoryPointsEarned += points;
	}

	public SpecialGoal popSpecialGoal() {
		if (pendingSpecialGoal != null) {
			SpecialGoal temp = this.pendingSpecialGoal;
			this.pendingSpecialGoal = null;
			return temp;
		} else {
			return null;
		}
	}

	public void setInvasionDefinition(InvasionDefinition invasionDefinition) {
		this.invasionDefinition = invasionDefinition;
	}

	private void removeCampfire(GameContext gameContext, MessageDispatcher messageDispatcher) {
		gameContext.getAreaMap().getTile(homeLocation).getEntities()
				.stream().filter(e -> e.getPhysicalEntityComponent().getAttributes() instanceof FurnitureEntityAttributes attributes &&
						attributes.getFurnitureType().getName().equals(CreateCampfireAction.CAMPFIRE_FURNITURE_TYPE_NAME))
				.findAny()
				.ifPresent(entity -> {
					messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, entity);
				});
	}

	public InvasionStage getInvasionStage() {
		return invasionStage;
	}

	public void killedEnemy(Entity deceased) {
		if (deceased.getOrCreateComponent(FactionComponent.class).getFaction().equals(Faction.SETTLEMENT)) {
			addVictoryPoints(VictoryPointsForStealingAction.VICTORY_POINTS_FOR_KILLING_SETTLER);
		}
	}

	@Override
	public void removeMemberId(long entityId) {
		super.removeMemberId(entityId);
		this.victoryPointsTarget -= POINTS_LOST_ON_MEMBER_DEATH;
		if (invasionStage.equals(InvasionStage.ARRIVING) || invasionStage.equals(InvasionStage.PREPARING)) {
			memberLostBeforeRaid = true;
		}
	}

	private boolean isBeaten() {
		return memberEntityIds.size() * 2 < strongestMemberCount;
	}

	/**
	 * Switches to RETREATING and makes members still fighting break off (as a squad's retreat order does,
	 * see MilitaryMessageHandler), so they leave instead of fighting on until their combat ends by itself.
	 */
	private void retreatNow(GameContext gameContext) {
		this.invasionStage = InvasionStage.RETREATING;
		this.hoursInCurrentStage = 0;
		for (Long memberId : memberEntityIds) {
			Entity member = gameContext.getEntities().get(memberId);
			if (member != null) {
				CombatStateComponent combatState = member.getComponent(CombatStateComponent.class);
				if (combatState != null && combatState.isInCombat()) {
					combatState.setForceRetreat(true);
				} else if (member.getBehaviourComponent() instanceof CreatureBehaviour creatureBehaviour
						&& creatureBehaviour.getCurrentGoal() != null) {
					// Drop the raid goal it is on (going to attack or steal) so it picks the retreat goal next
					creatureBehaviour.getCurrentGoal().setInterrupted(true);
				}
			}
		}
	}

	private boolean anyMemberInCombat(GameContext gameContext) {
		for (Long memberId : memberEntityIds) {
			Entity member = gameContext.getEntities().get(memberId);
			if (member != null) {
				CombatStateComponent combatState = member.getComponent(CombatStateComponent.class);
				if (combatState != null && combatState.isInCombat()) {
					return true;
				}
			}
		}
		return false;
	}

	// The most fighters this group has had, to tell a beaten group (more than half lost) from a wounded one
	private int strongestMemberCount;
	// A member fell before the raid began; picked up on the next update (not saved: a load re-checks combat)
	private transient boolean memberLostBeforeRaid;

	@Override
	public void writeTo(SavedGameStateHolder savedGameStateHolder) {
		super.writeTo(savedGameStateHolder);

		JSONObject asJson = savedGameStateHolder.creatureGroupJson.getJSONObject(savedGameStateHolder.creatureGroupJson.size() - 1);
		if (asJson.getLongValue("groupId") == this.groupId) {
			asJson.put("_class", getClass().getName());

			asJson.put("invasionDefinition", invasionDefinition.getName());

			if (!invasionStage.equals(InvasionStage.ARRIVING)) {
				asJson.put("invasionStage", invasionStage.name());
			}
			asJson.put("hoursInCurrentStage", hoursInCurrentStage);

			if (pendingSpecialGoal != null) {
				asJson.put("pendingSpecialGoal", pendingSpecialGoal.name());
			}

			asJson.put("victoryPointsEarned", victoryPointsEarned);
			asJson.put("victoryPointsTarget", victoryPointsTarget);
			asJson.put("strongestMemberCount", strongestMemberCount);
		}
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		super.readFrom(asJson, savedGameStateHolder, relatedStores);

		this.invasionDefinition = relatedStores.invasionDefinitionDictionary.getByName(asJson.getString("invasionDefinition"));
		if (this.invasionDefinition == null) {
			throw new InvalidSaveException("Could not find invasion definition with name " + asJson.getString("invasionDefinition"));
		}

		this.invasionStage = EnumParser.getEnumValue(asJson, "invasionStage", InvasionStage.class, InvasionStage.ARRIVING);
		this.hoursInCurrentStage = asJson.getDoubleValue("hoursInCurrentStage");

		this.pendingSpecialGoal = EnumParser.getEnumValue(asJson, "pendingSpecialGoal", SpecialGoal.class, null);

		this.victoryPointsEarned = asJson.getIntValue("victoryPointsEarned");
		this.victoryPointsTarget = asJson.getIntValue("victoryPointsTarget");
		this.strongestMemberCount = asJson.getIntValue("strongestMemberCount");
	}
}
