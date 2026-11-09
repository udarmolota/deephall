package technology.rocketjump.mountaincore.settlement;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import technology.rocketjump.mountaincore.assets.entities.item.model.ItemPlacement;
import technology.rocketjump.mountaincore.entities.components.Faction;
import technology.rocketjump.mountaincore.entities.components.InventoryComponent;
import technology.rocketjump.mountaincore.entities.components.ItemAllocation;
import technology.rocketjump.mountaincore.entities.components.ItemAllocationComponent;
import technology.rocketjump.mountaincore.entities.factories.ItemEntityFactory;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.Updatable;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.messaging.MessageType;

import java.util.ArrayList;

/**
 * Settlers arrive with fifty rations in their pockets, and food in a pocket belongs to that settler alone: in a
 * famine three new arrivals ate well while everyone else starved beside them. A settler keeps about a day's worth
 * (two meals a day) and puts the rest down at its feet, from where it is hauled to storage like any other food.
 * Pockets are checked every morning, once settlers are up, and once straight after a game is loaded. Only a settler
 * standing in the settlement itself puts food down, so the surplus is not left at the edge of the map.
 */
@Singleton
public class PocketRations implements Updatable, Telegraph {

	private static final String RATION_ITEM_TYPE = "Product-Ration";
	private static final int RATIONS_KEPT = 3;
	private static final int MORNING_HOUR = 8;

	private final SettlerTracker settlerTracker;
	private final ItemEntityFactory itemEntityFactory;
	private final MessageDispatcher messageDispatcher;
	private GameContext gameContext;
	private boolean checkedSinceLoad;

	@Inject
	public PocketRations(SettlerTracker settlerTracker, ItemEntityFactory itemEntityFactory, MessageDispatcher messageDispatcher) {
		this.settlerTracker = settlerTracker;
		this.itemEntityFactory = itemEntityFactory;
		this.messageDispatcher = messageDispatcher;
		messageDispatcher.addListener(this, MessageType.HOUR_ELAPSED);
	}

	@Override
	public void update(float deltaTime) {
		if (gameContext != null && !checkedSinceLoad) {
			checkedSinceLoad = true;
			checkPockets();
		}
	}

	@Override
	public boolean handleMessage(Telegram msg) {
		if (msg.message == MessageType.HOUR_ELAPSED) {
			if (gameContext != null && gameContext.getGameClock().getHourOfDay() == MORNING_HOUR) {
				checkPockets();
			}
			return false;
		}
		throw new IllegalArgumentException("Unexpected message type " + msg.message + " received by " + getClass().getSimpleName());
	}

	private void checkPockets() {
		int settlementRegion = ImmigrationManager.determineSettlementRegionId(gameContext, true);
		for (Entity settler : new ArrayList<>(settlerTracker.getLiving())) {
			putDownSurplus(settler, settlementRegion);
		}
	}

	private void putDownSurplus(Entity settler, int settlementRegion) {
		InventoryComponent inventory = settler.getComponent(InventoryComponent.class);
		if (inventory == null || settler.getLocationComponent().getWorldOrParentPosition() == null) {
			return;
		}
		MapTile tile = gameContext.getAreaMap().getTile(settler.getLocationComponent().getWorldOrParentPosition());
		if (tile == null || tile.getRegionId() != settlementRegion || !tile.isNavigable(null)) {
			return;
		}
		for (InventoryComponent.InventoryEntry entry : new ArrayList<>(inventory.getInventoryEntries())) {
			if (!(entry.entity.getPhysicalEntityComponent().getAttributes() instanceof ItemEntityAttributes attributes)
					|| !RATION_ITEM_TYPE.equals(attributes.getItemType().getItemTypeName())
					|| attributes.getQuantity() <= RATIONS_KEPT) {
				continue;
			}
			ItemAllocationComponent allocations = entry.entity.getOrCreateComponent(ItemAllocationComponent.class);
			if (allocations.getAll().stream().anyMatch(allocation -> allocation.getPurpose() != ItemAllocation.Purpose.HELD_IN_INVENTORY)) {
				// Being eaten or carried somewhere right now: try again on a later check
				continue;
			}
			int surplus = attributes.getQuantity() - RATIONS_KEPT;

			allocations.cancelAll(ItemAllocation.Purpose.HELD_IN_INVENTORY);
			attributes.setQuantity(RATIONS_KEPT);
			allocations.createAllocation(RATIONS_KEPT, settler, ItemAllocation.Purpose.HELD_IN_INVENTORY);
			messageDispatcher.dispatchMessage(MessageType.ENTITY_ASSET_UPDATE_REQUIRED, entry.entity);

			ItemEntityAttributes surplusAttributes = attributes.clone();
			surplusAttributes.setQuantity(surplus);
			surplusAttributes.setItemPlacement(ItemPlacement.ON_GROUND);
			itemEntityFactory.create(surplusAttributes, tile.getTilePosition(), true, gameContext, Faction.SETTLEMENT);
		}
	}

	@Override
	public boolean runWhilePaused() {
		return false;
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
		checkedSinceLoad = false;
	}

	@Override
	public void clearContextRelatedState() {
		gameContext = null;
		checkedSinceLoad = false;
	}
}
