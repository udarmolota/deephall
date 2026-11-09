package technology.rocketjump.mountaincore.entities.ai.goap.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import technology.rocketjump.mountaincore.entities.ai.memory.Memory;
import technology.rocketjump.mountaincore.entities.ai.memory.MemoryType;
import technology.rocketjump.mountaincore.entities.components.creature.MemoryComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;

import java.util.Collection;

public class GoalSelectionByMemory implements GoalSelectionCondition {

	public final MemoryType memoryType;
	// "absent": true turns the condition round: it holds while there is no such memory
	public final boolean absent;

	@JsonCreator
	public GoalSelectionByMemory(@JsonProperty("memoryType") MemoryType memoryType, @JsonProperty("absent") Boolean absent) {
		this.memoryType = memoryType;
		this.absent = absent != null && absent;
	}

	@JsonIgnore
	@Override
	public boolean apply(Entity parentEntity, GameContext gameContext) {
		return absent != remembers(parentEntity, gameContext);
	}

	private boolean remembers(Entity parentEntity, GameContext gameContext) {
		MemoryComponent memoryComponent = parentEntity.getComponent(MemoryComponent.class);
		if (memoryComponent == null) {
			return false;
		}

		Collection<Memory> shortTermMemories = memoryComponent.getShortTermMemories(gameContext.getGameClock());
		for (Memory shortTermMemory : shortTermMemories) {
			if (shortTermMemory.getType().equals(memoryType)) {
				return true;
			}
		}
		return false;
	}
}
