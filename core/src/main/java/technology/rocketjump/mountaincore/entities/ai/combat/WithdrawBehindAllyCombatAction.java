package technology.rocketjump.mountaincore.entities.ai.combat;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.math.Vector2;
import technology.rocketjump.mountaincore.entities.ai.goap.AssignedGoal;
import technology.rocketjump.mountaincore.entities.ai.goap.Goal;
import technology.rocketjump.mountaincore.entities.ai.goap.actions.Action;
import technology.rocketjump.mountaincore.entities.ai.goap.actions.location.GoToLocationAction;
import technology.rocketjump.mountaincore.entities.components.creature.CombatStateComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;

/**
 * A badly wounded fighter steps back behind a healthy comrade who is facing the same opponent, and from there
 * only defends (CombatBehaviour picks DefensiveCombatAction while it stays behind). It stays in the fight, so it
 * steps in again if the comrade falls. Chosen by CombatBehaviour#withdrawBehindAllyAction.
 */
public class WithdrawBehindAllyCombatAction extends CombatAction {

	private transient Vector2 withdrawTo;
	private transient GoToLocationAction goToLocationAction;

	public WithdrawBehindAllyCombatAction(Entity parentEntity) {
		super(parentEntity);
	}

	public void setWithdrawTo(Vector2 withdrawTo) {
		this.withdrawTo = withdrawTo;
	}

	@Override
	public void update(float deltaTime, GameContext gameContext, MessageDispatcher messageDispatcher) throws ExitingCombatException {
		if (withdrawTo == null) {
			// e.g. after loading a save mid-step: simply done, the next round decides again
			completed = true;
			return;
		}
		if (goToLocationAction == null) {
			goToLocationAction = new GoToLocationAction(new AssignedGoal(Goal.NULL_GOAL, parentEntity, messageDispatcher, gameContext));
			goToLocationAction.setOverrideLocation(withdrawTo);
			parentEntity.getComponent(CombatStateComponent.class).setHeldLocation(null);
		}

		goToLocationAction.update(deltaTime, gameContext);

		Action.CompletionType completion = MoveInRangeOfTargetCombatAction.isCompleted(goToLocationAction, gameContext);
		if (completion != null) {
			// Reached, or no way there: either way it now stands its ground defending
			parentEntity.getBehaviourComponent().getSteeringComponent().destinationReached();
			completed = true;
		}
	}

	@Override
	public void interrupted(MessageDispatcher messageDispatcher) {
	}

	@Override
	public boolean completesInOneRound() {
		return false;
	}
}
