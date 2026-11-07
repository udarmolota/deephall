package technology.rocketjump.mountaincore.entities.tags;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import technology.rocketjump.mountaincore.entities.components.PerishableComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.environment.model.Season;
import technology.rocketjump.mountaincore.gamecontext.GameContext;

/**
 * Food that rots: "PERISHABLE": [days in summer, days in spring and autumn, days in winter] on an item type.
 * The item gets a PerishableComponent, which counts down its freshness.
 */
public class PerishableTag extends Tag {

	@Override
	public String getTagName() {
		return "PERISHABLE";
	}

	@Override
	public boolean isValid(TagProcessingUtils tagProcessingUtils) {
		if (args.size() != 3) {
			return false;
		}
		try {
			for (String arg : args) {
				if (Float.parseFloat(arg) <= 0) {
					return false;
				}
			}
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public void apply(Entity entity, TagProcessingUtils tagProcessingUtils, MessageDispatcher messageDispatcher, GameContext gameContext) {
		if (entity.getComponent(PerishableComponent.class) == null) {
			PerishableComponent component = new PerishableComponent();
			component.init(entity, messageDispatcher, gameContext);
			entity.addComponent(component);
		}
	}

	public float getShelfLifeDays(Season season) {
		return switch (season) {
			case SUMMER -> Float.parseFloat(args.get(0));
			case WINTER -> Float.parseFloat(args.get(2));
			default -> Float.parseFloat(args.get(1));
		};
	}
}
