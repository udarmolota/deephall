package technology.rocketjump.mountaincore.entities.ai.goap.actions;

import com.alibaba.fastjson.JSONObject;
import technology.rocketjump.mountaincore.entities.components.creature.MilitaryComponent;
import technology.rocketjump.mountaincore.entities.ai.goap.AssignedGoal;
import technology.rocketjump.mountaincore.entities.components.Faction;
import technology.rocketjump.mountaincore.entities.components.FactionComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.messaging.types.FurnitureAssignmentRequest;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;

public class LocateBedAction extends Action implements FurnitureAssignmentCallback {

	public LocateBedAction(AssignedGoal parent) {
		super(parent);
	}

	boolean willingToSleepOnFloor = false;
	// Military settlers use the barracks first, then any bed that is free tonight
	boolean willingToUseAnyBed = false;

	@Override
	public void update(float deltaTime, GameContext gameContext) {
		if (parent.parentEntity.isSettler()) {
			parent.messageDispatcher.dispatchMessage(MessageType.REQUEST_FURNITURE_ASSIGNMENT,
					FurnitureAssignmentRequest.requestBed(parent.parentEntity, willingToSleepOnFloor, willingToUseAnyBed, this::furnitureAssigned));
		} else {
			// For animals / non-settlers
			completionType = CompletionType.FAILURE;
		}
	}

	@Override
	public void furnitureAssigned(Entity furnitureEntity) {
		if (furnitureEntity == null) {
			if (!willingToSleepOnFloor) {
				willingToSleepOnFloor = true;
				requestBedAgain();
			} else if (!willingToUseAnyBed && isInMilitary()) {
				willingToUseAnyBed = true;
				willingToSleepOnFloor = false;
				requestBedAgain();
			} else {
				completionType = CompletionType.FAILURE;
			}
		} else {
			parent.setAssignedFurnitureId(furnitureEntity.getId());
			completionType = CompletionType.SUCCESS;
		}
	}

	private void requestBedAgain() {
		parent.messageDispatcher.dispatchMessage(MessageType.REQUEST_FURNITURE_ASSIGNMENT,
				FurnitureAssignmentRequest.requestBed(parent.parentEntity, willingToSleepOnFloor, willingToUseAnyBed, this));
	}

	private boolean isInMilitary() {
		MilitaryComponent militaryComponent = parent.parentEntity.getComponent(MilitaryComponent.class);
		return militaryComponent != null && militaryComponent.isInMilitary();
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
		// No state to write
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		// No state to read
	}
}
