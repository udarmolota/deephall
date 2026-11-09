package technology.rocketjump.mountaincore.settlement;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.math.GridPoint2;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import technology.rocketjump.mountaincore.entities.behaviour.creature.CorpseBehaviour;
import technology.rocketjump.mountaincore.entities.components.InventoryComponent;
import technology.rocketjump.mountaincore.entities.components.ItemAllocationComponent;
import technology.rocketjump.mountaincore.entities.components.furniture.ConstructedEntityComponent;
import technology.rocketjump.mountaincore.entities.components.furniture.DeceasedContainerComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.EntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.creature.CreatureEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.creature.Race;
import technology.rocketjump.mountaincore.entities.model.physical.furniture.FurnitureEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.furniture.FurnitureLayout;
import technology.rocketjump.mountaincore.entities.tags.DeceasedContainerTag;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.Updatable;
import technology.rocketjump.mountaincore.jobs.JobStore;
import technology.rocketjump.mountaincore.jobs.JobTypeDictionary;
import technology.rocketjump.mountaincore.jobs.model.Job;
import technology.rocketjump.mountaincore.jobs.model.JobPriority;
import technology.rocketjump.mountaincore.jobs.model.JobType;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.misc.VectorUtils;
import technology.rocketjump.mountaincore.rooms.HaulingAllocation;
import technology.rocketjump.mountaincore.rooms.HaulingAllocationBuilder;
import technology.rocketjump.mountaincore.settlement.notifications.Notification;
import technology.rocketjump.mountaincore.settlement.notifications.NotificationType;
import technology.rocketjump.mountaincore.ui.Selectable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static technology.rocketjump.mountaincore.entities.model.EntityType.CREATURE;

/**
 * This class is responsible for assigning and moving corpses to graves.
 *
 * <p>Bodies are taken in order of the priority set on them (CorpseBehaviour), so a body the player marks gets the
 * next free grave. A body with no free grave is flagged as waiting, which lets it go to a stockpile meanwhile, and
 * once a day the player is told that settlers lie unburied.
 */
@Singleton
public class GraveManager implements Updatable {

	private static final float TIME_BETWEEN_INFREQUENT_UPDATE_SECONDS = 3.4f;
	private static final double HOURS_BETWEEN_NOT_ENOUGH_GRAVES_WARNINGS = 24.0;

	private final MessageDispatcher messageDispatcher;
	private final SettlementFurnitureTracker settlementFurnitureTracker;
	private final SettlerTracker settlerTracker;
	private final CreatureTracker creatureTracker;
	private final JobStore jobStore;
	private final JobType haulingJobType;

	private GameContext gameContext;
	private float timeSinceLastUpdate = 0f;
	private double lastNotEnoughGravesWarning = Double.NEGATIVE_INFINITY;

	@Inject
	public GraveManager(MessageDispatcher messageDispatcher, SettlementFurnitureTracker settlementFurnitureTracker,
						SettlerTracker settlerTracker, CreatureTracker creatureTracker, JobStore jobStore,
						JobTypeDictionary jobTypeDictionary) {
		this.messageDispatcher = messageDispatcher;
		this.settlementFurnitureTracker = settlementFurnitureTracker;
		this.settlerTracker = settlerTracker;
		this.creatureTracker = creatureTracker;
		this.jobStore = jobStore;
		this.haulingJobType = jobTypeDictionary.getByName("HAULING");
	}

	@Override
	public void update(float deltaTime) {
		timeSinceLastUpdate += deltaTime;
		if (gameContext != null && timeSinceLastUpdate > TIME_BETWEEN_INFREQUENT_UPDATE_SECONDS) {
			timeSinceLastUpdate = 0f;

			List<Entity> allDead = new ArrayList<>();
			allDead.addAll(settlerTracker.getDead());
			allDead.addAll(creatureTracker.getDead());
			// Highest priority first (JobPriority is declared from highest to lowest); the sort is stable
			allDead.sort(Comparator.comparing(GraveManager::priorityOf));

			Entity unburiedSettler = null;
			for (Entity deceased : allDead) {
				CorpseBehaviour corpse = deceased.getBehaviourComponent() instanceof CorpseBehaviour c ? c : null;
				if (deceased.getLocationComponent().getContainerEntity() != null) {
					if (corpse != null) {
						corpse.setWaitingForGrave(false);
					}
					continue;
				}
				JobPriority priority = priorityOf(deceased);
				updateHaulingJobPriority(deceased, priority);
				if (priority == JobPriority.DISABLED) {
					continue;
				}
				if (!hasCorpseHaulingJob(deceased) && isUnallocated(deceased)) {
					Entity deceasedContainer = findAvailableDeceasedContainer(deceased);
					if (deceasedContainer != null) {
						assignAndCreateHaulingJob(deceased, deceasedContainer, priority);
						if (corpse != null) {
							corpse.setWaitingForGrave(false);
						}
					} else {
						if (corpse != null) {
							corpse.setWaitingForGrave(true);
						}
						if (unburiedSettler == null && isSettlerRace(deceased)) {
							unburiedSettler = deceased;
						}
					}
				}
			}
			warnIfNotEnoughGraves(unburiedSettler);
		}
	}

	private static JobPriority priorityOf(Entity deceased) {
		return deceased.getBehaviourComponent() instanceof CorpseBehaviour corpse ? corpse.getPriority() : CorpseBehaviour.DEFAULT_PRIORITY;
	}

	/** A priority changed on the body reaches the job already carrying it, to a grave or to a stockpile. */
	private void updateHaulingJobPriority(Entity deceased, JobPriority priority) {
		GridPoint2 location = VectorUtils.toGridPoint(deceased.getLocationComponent().getWorldOrParentPosition());
		if (location == null) {
			return;
		}
		for (Job job : jobStore.getJobsAtLocation(location)) {
			if (job.getType().equals(haulingJobType) && job.getHaulingAllocation() != null
					&& Long.valueOf(deceased.getId()).equals(job.getHaulingAllocation().getHauledEntityId()) && job.getJobPriority() != priority) {
				job.setJobPriority(priority);
			}
		}
	}

	private boolean isSettlerRace(Entity deceased) {
		return deceased.getPhysicalEntityComponent().getAttributes() instanceof CreatureEntityAttributes attributes
				&& attributes.getRace().equals(gameContext.getSettlementState().getSettlerRace());
	}

	/**
	 * A settler's body with no grave to go to lies where it fell, and every settler passing within sight of it is upset
	 * for a day: a body on a main corridor can tip a whole settlement into tantrums. Said once a day while it lasts.
	 */
	private void warnIfNotEnoughGraves(Entity unburiedSettler) {
		if (unburiedSettler == null) {
			return;
		}
		double now = gameContext.getGameClock().getCurrentGameTime();
		if (now - lastNotEnoughGravesWarning < HOURS_BETWEEN_NOT_ENOUGH_GRAVES_WARNINGS) {
			return;
		}
		lastNotEnoughGravesWarning = now;
		messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION, new Notification(NotificationType.NOT_ENOUGH_GRAVES,
				unburiedSettler.getLocationComponent().getWorldOrParentPosition().cpy(), new Selectable(unburiedSettler, 0)));
	}

	private boolean isUnallocated(Entity deceased) {
		ItemAllocationComponent itemAllocationComponent = deceased.getComponent(ItemAllocationComponent.class);
		return  itemAllocationComponent == null || itemAllocationComponent.getNumUnallocated() > 0;
	}

	private boolean hasCorpseHaulingJob(Entity deceased) {
		GridPoint2 location = VectorUtils.toGridPoint(deceased.getLocationComponent().getWorldOrParentPosition());
		if (location == null) {
			// FIXME When does this happen? Returning true for now so nothing else tries to access this corpse
			return true;
		}

		for (Job jobAtLocation : jobStore.getJobsAtLocation(location)) {
			if (jobAtLocation.getType().equals(haulingJobType) && jobAtLocation.getHaulingAllocation() != null &&
					jobAtLocation.getHaulingAllocation().getHauledEntityType().equals(CREATURE)) {
				return true;
			}
		}
		return false;
	}

	private Entity findAvailableDeceasedContainer(Entity deceased) {
		Race settlerRace = gameContext.getSettlementState().getSettlerRace();
		Race deceasedRace = null;
		EntityAttributes deceasedAttributes = deceased.getPhysicalEntityComponent().getAttributes();
		if (deceasedAttributes instanceof CreatureEntityAttributes creatureEntityAttributes) {
			deceasedRace = creatureEntityAttributes.getRace();
		}
		MapTile deceasedTile = gameContext.getAreaMap().getTile(deceased.getLocationComponent().getWorldPosition());

		for (Entity deceasedContainer : settlementFurnitureTracker.findByTag(DeceasedContainerTag.class, true)) {
			DeceasedContainerTag deceasedContainerTag = deceasedContainer.getTag(DeceasedContainerTag.class);
			ConstructedEntityComponent constructedEntityComponent = deceasedContainer.getComponent(ConstructedEntityComponent.class);

			int regionId = gameContext.getAreaMap().getNavigableRegionId(deceasedContainer, deceasedContainer.getLocationComponent().getWorldPosition());

			if (constructedEntityComponent != null && constructedEntityComponent.isBeingDeconstructed()) {
				continue;
			}
			if (!deceasedContainerTag.matchesRace(deceasedRace, settlerRace)) {
				continue;
			}
			if (deceasedTile == null || deceasedTile.getRegionId() != regionId) {
				continue;
			}

			if (FurnitureLayout.getAnyNavigableWorkspace(deceasedContainer, gameContext.getAreaMap()) != null) {
				InventoryComponent inventoryComponent = deceasedContainer.getComponent(InventoryComponent.class);
				if (deceasedContainerTag.getMaxCapacity() > 1) {

					int currentAllocationCount = deceasedContainer.getOrCreateComponent(DeceasedContainerComponent.class).getHaulingJobCount();
					if (inventoryComponent != null && (inventoryComponent.getInventoryEntries().size() + currentAllocationCount) >= deceasedContainerTag.getMaxCapacity()) {
						continue; // not enough space
					}
				}

				return deceasedContainer;
			}
		}
		return null;
	}

	private void assignAndCreateHaulingJob(Entity deceased, Entity deceasedContainer, JobPriority priority) {
		DeceasedContainerTag deceasedContainerTag = deceasedContainer.getTag(DeceasedContainerTag.class);
		if (deceasedContainerTag.getMaxCapacity() == 1) {
			FurnitureEntityAttributes furnitureAttributes = (FurnitureEntityAttributes) deceasedContainer.getPhysicalEntityComponent().getAttributes();
			furnitureAttributes.setAssignedToEntityId(deceased.getId());
		}

		HaulingAllocation allocation = HaulingAllocationBuilder.createToHaulCreature(deceased)
				.toEntity(deceasedContainer);


		Job haulingJob = new Job(haulingJobType);
		haulingJob.setJobPriority(priority);
		haulingJob.setTargetId(allocation.getHauledEntityId());
		haulingJob.setHaulingAllocation(allocation);
		haulingJob.setJobLocation(allocation.getSourcePosition());
		messageDispatcher.dispatchMessage(MessageType.JOB_CREATED, haulingJob);
		deceasedContainer.getOrCreateComponent(DeceasedContainerComponent.class).addHaulingJob(haulingJob);
	}

	@Override
	public boolean runWhilePaused() {
		return false;
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {
		gameContext = null;
		timeSinceLastUpdate = 0f;
		lastNotEnoughGravesWarning = Double.NEGATIVE_INFINITY;
	}
}
