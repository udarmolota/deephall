package technology.rocketjump.mountaincore.entities.components;

import technology.rocketjump.mountaincore.materials.model.GameMaterial;
import technology.rocketjump.mountaincore.materials.model.GameMaterialType;
import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.tags.PerishableTag;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.mapping.tile.roof.TileRoofState;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;
import technology.rocketjump.mountaincore.settlement.notifications.Notification;
import technology.rocketjump.mountaincore.settlement.notifications.NotificationType;

/**
 * Freshness of a stack of food (see PerishableTag). spoilage runs from 0 (fresh) to 1, when the whole stack has
 * rotted and is destroyed. It grows by the elapsed time over the shelf life for the current season, half as fast
 * again under a roof. When stacks merge the result takes the quantity-weighted average (mergeSpoilage), so topping
 * up an old stack with fresh food does not make the old food fresh.
 */
public class PerishableComponent implements InfrequentlyUpdatableComponent {

	private static final float UNDER_ROOF_SHELF_LIFE_MULTIPLIER = 1.5f;
	private static final double HOURS_IN_DAY = 24.0;
	private static final double MAX_CREDIBLE_ELAPSED_HOURS = 12.0;

	// The player hears about rotting food at most once a game day
	private static int lastNotificationDay = -1;

	private Entity parentEntity;
	private MessageDispatcher messageDispatcher;
	private GameContext gameContext;

	private double spoilage;

	@Override
	public void init(Entity parentEntity, MessageDispatcher messageDispatcher, GameContext gameContext) {
		this.parentEntity = parentEntity;
		this.messageDispatcher = messageDispatcher;
		this.gameContext = gameContext;
	}

	@Override
	public EntityComponent clone(MessageDispatcher messageDispatcher, GameContext gameContext) {
		PerishableComponent clone = new PerishableComponent();
		clone.messageDispatcher = messageDispatcher;
		clone.gameContext = gameContext;
		clone.spoilage = this.spoilage;
		return clone;
	}

	@Override
	public void infrequentUpdate(double elapsedTime) {
		// An entity loaded from a save can report the whole game time as elapsed on its first update (its last
		// update time was never set); real gaps are a game hour or so at most, so a huge one is ignored
		if (elapsedTime <= 0 || elapsedTime > MAX_CREDIBLE_ELAPSED_HOURS || gameContext == null
				|| !(parentEntity.getPhysicalEntityComponent().getAttributes() instanceof ItemEntityAttributes attributes)
				|| attributes.isDestroyed()) {
			return;
		}
		PerishableTag tag = parentEntity.getTag(PerishableTag.class);
		if (tag == null || keepsDried(attributes)) {
			return;
		}
		double shelfLifeHours = tag.getShelfLifeDays(gameContext.getGameClock().getCurrentSeason()) * HOURS_IN_DAY;
		MapTile tile = gameContext.getAreaMap().getTile(parentEntity.getLocationComponent().getWorldOrParentPosition());
		if (tile != null && !tile.getRoof().getState().equals(TileRoofState.OPEN)) {
			shelfLifeHours *= UNDER_ROOF_SHELF_LIFE_MULTIPLIER;
		}
		spoilage += elapsedTime / shelfLifeHours;

		if (spoilage >= 1.0) {
			int day = gameContext.getGameClock().getCurrentDayNumber();
			if (day != lastNotificationDay) {
				lastNotificationDay = day;
				messageDispatcher.dispatchMessage(MessageType.POST_NOTIFICATION, new Notification(NotificationType.FOOD_ROTTED,
						parentEntity.getLocationComponent().getWorldOrParentPosition().cpy(), null));
			}
			messageDispatcher.dispatchMessage(MessageType.DESTROY_ENTITY, parentEntity);
		}
	}

	/**
	 * A crop nobody eats - hops, grain, hemp - goes into the same sacks as vegetables but is dried and keeps: hops
	 * rotting in a season left a brewery with nothing to brew.
	 */
	private static boolean keepsDried(ItemEntityAttributes attributes) {
		GameMaterial material = attributes.getPrimaryMaterial();
		return material != null && material.getMaterialType() == GameMaterialType.VEGETABLE && !material.isEdible()
				&& attributes.getItemType().getItemTypeName().startsWith("Ingredient-Vegetable-");
	}

	public double getSpoilage() {
		return spoilage;
	}

	/**
	 * Call when addedQuantity items with addedSpoilage are merged into target, which held targetQuantityBefore.
	 * Either entity may be without the component (non-perishable, or newly made and so fresh: spoilage 0).
	 */
	public static void mergeSpoilage(Entity target, int targetQuantityBefore, double addedSpoilage, int addedQuantity) {
		PerishableComponent targetComponent = target.getComponent(PerishableComponent.class);
		if (targetComponent == null || targetQuantityBefore + addedQuantity <= 0) {
			return;
		}
		targetComponent.spoilage = (targetComponent.spoilage * targetQuantityBefore + addedSpoilage * addedQuantity)
				/ (targetQuantityBefore + addedQuantity);
	}

	public static double spoilageOf(Entity entity) {
		PerishableComponent component = entity.getComponent(PerishableComponent.class);
		return component == null ? 0 : component.spoilage;
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
		if (spoilage > 0) {
			asJson.put("spoilage", spoilage);
		}
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		this.spoilage = asJson.getDoubleValue("spoilage");
	}
}
