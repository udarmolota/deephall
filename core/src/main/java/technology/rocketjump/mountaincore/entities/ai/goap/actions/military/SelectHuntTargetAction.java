package technology.rocketjump.mountaincore.entities.ai.goap.actions.military;

import com.alibaba.fastjson.JSONObject;
import technology.rocketjump.mountaincore.entities.ai.goap.AssignedGoal;
import technology.rocketjump.mountaincore.entities.ai.goap.SwitchGoalException;
import technology.rocketjump.mountaincore.entities.ai.goap.actions.Action;
import technology.rocketjump.mountaincore.entities.components.creature.CombatStateComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;
import technology.rocketjump.mountaincore.settlement.HuntingRules;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The hunter's counterpart of SelectOpponentAction: picks the nearest animal marked for the hunt, preferring one
 * no other hunter is already after, so two hunters spread over two animals. The rest of the hunt is the squad
 * attack chain (MoveTowardsOpponent, AttackOpponent).
 */
public class SelectHuntTargetAction extends Action {

	public SelectHuntTargetAction(AssignedGoal parent) {
		super(parent);
	}

	@Override
	public void update(float deltaTime, GameContext gameContext) throws SwitchGoalException {
		CombatStateComponent combatStateComponent = parent.parentEntity.getComponent(CombatStateComponent.class);
		List<Entity> targets = HuntingRules.livingHuntTargets(gameContext);
		if (combatStateComponent == null || targets.isEmpty()) {
			completionType = CompletionType.FAILURE;
			return;
		}

		Set<Long> takenByOtherHunters = gameContext.getEntities().values().stream()
				.filter(entity -> entity.getId() != parent.parentEntity.getId())
				.filter(HuntingRules::isHunter)
				.map(entity -> entity.getComponent(CombatStateComponent.class))
				.filter(Objects::nonNull)
				.map(CombatStateComponent::getTargetedOpponentId)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());

		Comparator<Entity> nearestFirst = Comparator.comparingDouble(target -> parent.parentEntity.getLocationComponent()
				.getWorldOrParentPosition().dst2(target.getLocationComponent().getWorldOrParentPosition()));
		Entity target = targets.stream()
				.filter(t -> !takenByOtherHunters.contains(t.getId()))
				.min(nearestFirst)
				.orElseGet(() -> targets.stream().min(nearestFirst).orElseThrow());

		combatStateComponent.setOpponentEntityIds(targets.stream().map(Entity::getId).collect(Collectors.toSet()));
		combatStateComponent.setTargetedOpponentId(target.getId());
		completionType = CompletionType.SUCCESS;
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
	}
}
