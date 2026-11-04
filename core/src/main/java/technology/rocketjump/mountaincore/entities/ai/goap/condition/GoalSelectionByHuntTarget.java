package technology.rocketjump.mountaincore.entities.ai.goap.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.settlement.HuntingRules;

/**
 * A hunter who is armed, carries ammunition, and has an animal marked for the hunt to go after.
 */
public class GoalSelectionByHuntTarget implements GoalSelectionCondition {

	@JsonCreator
	public GoalSelectionByHuntTarget() {
	}

	@JsonIgnore
	@Override
	public boolean apply(Entity parentEntity, GameContext gameContext) {
		return HuntingRules.isReadyToHunt(parentEntity, gameContext) && !HuntingRules.livingHuntTargets(gameContext).isEmpty();
	}
}
