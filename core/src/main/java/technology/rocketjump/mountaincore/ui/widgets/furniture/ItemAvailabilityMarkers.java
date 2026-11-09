package technology.rocketjump.mountaincore.ui.widgets.furniture;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import technology.rocketjump.mountaincore.entities.components.Faction;
import technology.rocketjump.mountaincore.entities.components.FactionComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.EntityType;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.gamecontext.GameContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Marks under each item type in a pallet's choice of item types: a green tick when a trader in the settlement has it
 * with them, and how many the settlement already holds (in storage, furniture and settlers' hands). Lets a player
 * pick what to buy or sell without walking round the depot and tapping every pile.
 */
class ItemAvailabilityMarkers {

	private static final Color MERCHANT_HAS_IT_COLOR = new Color(0.3f, 0.9f, 0.3f, 1f);

	private final Set<ItemType> merchantGoods = new HashSet<>();
	private final Map<ItemType, Integer> settlementStock = new HashMap<>();

	/** Called each time the choice is opened, so it shows the state of the moment. */
	void count(GameContext gameContext) {
		merchantGoods.clear();
		settlementStock.clear();
		if (gameContext == null) {
			return;
		}
		for (Entity entity : gameContext.getEntities().values()) {
			if (entity.getType() != EntityType.ITEM || !(entity.getPhysicalEntityComponent().getAttributes() instanceof ItemEntityAttributes attributes)) {
				continue;
			}
			FactionComponent factionComponent = entity.getComponent(FactionComponent.class);
			Faction faction = factionComponent == null ? null : factionComponent.getFaction();
			if (Faction.MERCHANTS.equals(faction)) {
				// In a trader's cart or hands, or already set out at the depot
				merchantGoods.add(attributes.getItemType());
			} else if (Faction.SETTLEMENT.equals(faction)) {
				settlementStock.merge(attributes.getItemType(), attributes.getQuantity(), Integer::sum);
			}
		}
	}

	Table row(ItemType itemType, Skin skin) {
		Table row = new Table();
		if (merchantGoods.contains(itemType)) {
			Image tick = new Image(skin.getDrawable("btn_tinycheck_tickbox"));
			tick.setColor(MERCHANT_HAS_IT_COLOR);
			row.add(tick).size(33, 33).padRight(10);
		}
		Integer held = settlementStock.get(itemType);
		if (held != null && held > 0) {
			row.add(new Label(String.valueOf(held), skin));
		}
		return row;
	}
}
