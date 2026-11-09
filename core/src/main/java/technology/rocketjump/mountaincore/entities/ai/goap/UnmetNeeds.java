package technology.rocketjump.mountaincore.entities.ai.goap;

import technology.rocketjump.mountaincore.entities.ai.memory.Memory;
import technology.rocketjump.mountaincore.entities.ai.memory.MemoryType;
import technology.rocketjump.mountaincore.entities.components.creature.MemoryComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;

/**
 * A low need stops work and sleep so a settler goes and deals with it. When there is nothing to deal with it with -
 * no food or drink anywhere it can reach - that turns into a trap: the fisher cannot fish, the cook cannot cook,
 * nobody carries food in and nobody sleeps, and the settlement starves with a fishing rod in its hands.
 * The eat and drink goals remember failing to find anything (FAILED_GOAL, a couple of hours); for that long the need
 * does not stop anything, and the settler works and sleeps until the goal is tried again.
 */
public final class UnmetNeeds {

	private UnmetNeeds() {
	}

	public static boolean nothingToMeet(Entity entity, EntityNeed need, GameContext gameContext) {
		String goalName = switch (need) {
			case FOOD -> "Eat food goal";
			case DRINK -> "Drink goal";
			default -> null;
		};
		if (goalName == null) {
			return false;
		}
		MemoryComponent memoryComponent = entity.getComponent(MemoryComponent.class);
		if (memoryComponent == null) {
			return false;
		}
		for (Memory memory : memoryComponent.getShortTermMemories(gameContext.getGameClock())) {
			if (memory.getType() == MemoryType.FAILED_GOAL && goalName.equals(memory.getRelatedGoalName())) {
				return true;
			}
		}
		return false;
	}
}
