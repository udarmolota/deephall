package technology.rocketjump.mountaincore.entities.behaviour.furniture;

import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import technology.rocketjump.mountaincore.assets.entities.furniture.model.DoorState;
import technology.rocketjump.mountaincore.audio.model.SoundAsset;
import technology.rocketjump.mountaincore.entities.components.BehaviourComponent;
import technology.rocketjump.mountaincore.entities.components.creature.SteeringComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.PhysicalEntityComponent;
import technology.rocketjump.mountaincore.entities.model.physical.furniture.FurnitureEntityAttributes;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.messaging.types.RequestSoundMessage;
import technology.rocketjump.mountaincore.misc.VectorUtils;
import technology.rocketjump.mountaincore.persistence.EnumParser;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;

import static technology.rocketjump.mountaincore.assets.entities.furniture.model.DoorState.*;

public class DoorBehaviour implements BehaviourComponent {

	public static final float ANIMATION_TIME = 0.4f;

	private MessageDispatcher messageDispatcher;
	private Entity parentEntity;
	private GameContext gameContext;

	private float currentStateElapsedTime = 0;
	private boolean doorOpenRequested;
	private DoorState state = DoorState.CLOSED;
	private SoundAsset openSound;
	private SoundAsset closeSound;

	@Override
	public void init(Entity parentEntity, MessageDispatcher messageDispatcher, GameContext gameContext) {
		this.messageDispatcher = messageDispatcher;
		this.parentEntity = parentEntity;
		this.gameContext = gameContext;
	}

	@Override
	public DoorBehaviour clone(MessageDispatcher messageDispatcher, GameContext gameContext) {
		DoorBehaviour cloned = new DoorBehaviour();
		cloned.init(parentEntity, messageDispatcher, gameContext);
		cloned.currentStateElapsedTime = this.currentStateElapsedTime;
		cloned.doorOpenRequested = this.doorOpenRequested;
		cloned.state = this.state;
		cloned.bashDamage = this.bashDamage;
		return cloned;
	}

	public DoorState getState() {
		return state;
	}

	public void setState(DoorState state) {
		this.state = state;
	}

	public void doorOpenRequested() {
		if (state.equals(CLOSED) || state.equals(CLOSING)) {
			doorOpenRequested = true;
		}
	}

	// Breaking doors down: invaders do not open doors, they bash them (SteeringComponent). Each invader standing at
	// the door adds its time to the damage; when the damage reaches the door's strength the whole doorway is gone
	// (DOOR_BROKEN_DOWN), leaving a gap in the wall for the player to rebuild.
	private static final float WOOD_DOOR_STRENGTH = 20f;  // seconds of bashing by one invader: about a game hour
	private static final float STONE_DOOR_STRENGTH = 40f;
	private static final float METAL_DOOR_STRENGTH = 60f;
	private static final float SECONDS_BETWEEN_BASH_SOUNDS = 1.5f;

	private float bashDamage;
	private transient boolean brokenDown;
	private transient float secondsToNextBashSound;

	public void bashed(float deltaTime) {
		if (brokenDown || state.equals(OPEN) || state.equals(OPENING)) {
			return;
		}
		bashDamage += deltaTime;
		secondsToNextBashSound -= deltaTime;
		if (secondsToNextBashSound <= 0 && closeSound != null) {
			secondsToNextBashSound = SECONDS_BETWEEN_BASH_SOUNDS;
			messageDispatcher.dispatchMessage(MessageType.REQUEST_SOUND, new RequestSoundMessage(closeSound, parentEntity.getId(),
					parentEntity.getLocationComponent().getWorldOrParentPosition(), null));
		}
		if (bashDamage >= strength()) {
			MapTile doorTile = gameContext.getAreaMap().getTile(parentEntity.getLocationComponent().getWorldPosition());
			if (doorTile != null && doorTile.hasDoorway()) {
				brokenDown = true;
				// Delayed: this is called from a creature's update, so the door is not destroyed mid-update
				messageDispatcher.dispatchMessage(0.01f, MessageType.DOOR_BROKEN_DOWN, doorTile.getDoorway());
			}
		}
	}

	private float strength() {
		if (parentEntity.getPhysicalEntityComponent().getAttributes() instanceof FurnitureEntityAttributes attributes
				&& attributes.getPrimaryMaterial() != null) {
			return switch (attributes.getPrimaryMaterial().getMaterialType()) {
				case STONE -> STONE_DOOR_STRENGTH;
				case METAL -> METAL_DOOR_STRENGTH;
				default -> WOOD_DOOR_STRENGTH;
			};
		}
		return WOOD_DOOR_STRENGTH;
	}

	public void setSoundAssets(SoundAsset openSound, SoundAsset closeSound) {
		this.openSound = openSound;
		this.closeSound = closeSound;
	}

	@Override
	public void update(float deltaTime) {
		currentStateElapsedTime += deltaTime;

		PhysicalEntityComponent physicalEntityComponent = parentEntity.getPhysicalEntityComponent();
		float currentAnimationProgress = physicalEntityComponent.getAnimationProgress();

		MapTile doorTile = gameContext.getAreaMap().getTile(parentEntity.getLocationComponent().getWorldPosition());
		boolean otherEntityInTile = doorTile.getEntities().size() > 1;


		if (doorOpenRequested) {
			switchState(OPENING);
			doorOpenRequested = false;
		} else {
			switch (state) {
				case CLOSED:
					break;
				case OPENING:
					currentAnimationProgress += (deltaTime / ANIMATION_TIME);
					if (currentAnimationProgress >= 1f) {
						currentAnimationProgress = 1f;
						switchState(OPEN);
					}
					physicalEntityComponent.setAnimationProgress(currentAnimationProgress);
					break;
				case OPEN:
					// Check for entities in doorway, if so, start closing
					if (otherEntityInTile) {
						currentStateElapsedTime = 0f;
					}

					if (currentStateElapsedTime > 1) {
						switchState(CLOSING);
					}
					break;
				case CLOSING:
					currentAnimationProgress -= (deltaTime / ANIMATION_TIME);

					if (otherEntityInTile) {
						switchState(OPENING);
					} else if (currentAnimationProgress <= 0f) {
						currentAnimationProgress = 0f;
						switchState(CLOSED);
					}
					physicalEntityComponent.setAnimationProgress(currentAnimationProgress);
					break;
			}
		}

	}

	@Override
	public void updateWhenPaused() {

	}

	private void switchState(DoorState newState) {
		DoorState previousState = this.state;
		if (previousState.equals(newState)) {
			return;
		} else {
			currentStateElapsedTime = 0;
		}
		this.state = newState;

		if (previousState.equals(CLOSED) && newState.equals(OPENING)) {
			messageDispatcher.dispatchMessage(MessageType.REQUEST_SOUND, new RequestSoundMessage(openSound, parentEntity.getId(),
					parentEntity.getLocationComponent().getWorldOrParentPosition(), null));
		} else if (previousState.equals(OPEN) && newState.equals(CLOSING)) {
			messageDispatcher.dispatchMessage(MessageType.REQUEST_SOUND, new RequestSoundMessage(closeSound, parentEntity.getId(),
					parentEntity.getLocationComponent().getWorldOrParentPosition(), null));
		}

		if (previousState.equals(CLOSED) || newState.equals(CLOSED)) {
			messageDispatcher.dispatchMessage(MessageType.DOOR_OPENED_OR_CLOSED, VectorUtils.toGridPoint(parentEntity.getLocationComponent().getWorldPosition()));
		}
	}


	@Override
	public void infrequentUpdate(GameContext gameContext) {
		// Do nothing, does not update infrequently
	}

	@Override
	public SteeringComponent getSteeringComponent() {
		return null;
	}

	@Override
	public boolean isUpdateEveryFrame() {
		return true;
	}

	@Override
	public boolean isUpdateInfrequently() {
		return false;
	}

	@Override
	public boolean isJobAssignable() {
		return false;
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
		if (currentStateElapsedTime != 0f) {
			asJson.put("elapsedTime", currentStateElapsedTime);
		}
		if (doorOpenRequested) {
			asJson.put("openRequested", true);
		}
		if (!state.equals(CLOSED)) {
			asJson.put("state", state.name());
		}
		if (bashDamage > 0) {
			asJson.put("bashDamage", bashDamage);
		}

		if (openSound != null) {
			asJson.put("openSound", openSound.getName());
		}
		if (closeSound != null) {
			asJson.put("closeSound", closeSound.getName());
		}
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		this.currentStateElapsedTime = asJson.getFloatValue("elapsedTime");
		this.doorOpenRequested = asJson.getBooleanValue("openRequested");
		this.state = EnumParser.getEnumValue(asJson, "state", DoorState.class, DoorState.CLOSED);
		this.bashDamage = asJson.getFloatValue("bashDamage");

		String openSoundName = asJson.getString("openSound");
		if (openSoundName != null) {
			this.openSound = relatedStores.soundAssetDictionary.getByName(openSoundName);
			if (this.openSound == null) {
				throw new InvalidSaveException("Could not find sound by name " + openSoundName);
			}
		}
		String closeSoundName = asJson.getString("closeSound");
		if (closeSoundName != null) {
			this.closeSound = relatedStores.soundAssetDictionary.getByName(closeSoundName);
			if (this.closeSound == null) {
				throw new InvalidSaveException("Could not find sound by name " + closeSoundName);
			}
		}
	}
}
