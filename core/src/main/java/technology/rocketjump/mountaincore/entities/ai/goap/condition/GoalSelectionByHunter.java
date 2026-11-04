package technology.rocketjump.mountaincore.entities.ai.goap.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.settlement.HuntingRules;

/**
 * A civilian with the hunter profession, used to let hunters top up the ammunition for their weapon as soldiers do.
 */
public class GoalSelectionByHunter implements GoalSelectionCondition {

	@JsonCreator
	public GoalSelectionByHunter() {
	}

	@JsonIgnore
	@Override
	public boolean apply(Entity parentEntity, GameContext gameContext) {
		return HuntingRules.isHunter(parentEntity);
	}
}
