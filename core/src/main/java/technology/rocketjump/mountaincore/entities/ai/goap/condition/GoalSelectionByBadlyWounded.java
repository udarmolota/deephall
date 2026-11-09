package technology.rocketjump.mountaincore.entities.ai.goap.condition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import technology.rocketjump.mountaincore.entities.components.Faction;
import technology.rocketjump.mountaincore.entities.components.FactionComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.creature.CreatureEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.creature.body.BodyPartDamage;
import technology.rocketjump.mountaincore.entities.model.physical.creature.body.BodyPartDamageLevel;
import technology.rocketjump.mountaincore.gamecontext.GameContext;

/**
 * A creature with a bleeding wound or broken bones, which sends it to bed rest (see the sleep goal) until those
 * have healed. Bruises are not bad enough, and a destroyed body part never heals, so neither keeps it in bed.
 */
public class GoalSelectionByBadlyWounded implements GoalSelectionCondition {

	@JsonCreator
	public GoalSelectionByBadlyWounded() {
	}

	@JsonIgnore
	@Override
	public boolean apply(Entity parentEntity, GameContext gameContext) {
		return needsBedRest(parentEntity);
	}

	/**
	 * Bed rest is for the settlement's own people. An invader with the same wounds lay down to heal in the middle of
	 * the settlement it was raiding, eating and sleeping there; invaders fight or retreat instead.
	 */
	public static boolean needsBedRest(Entity entity) {
		FactionComponent factionComponent = entity.getComponent(FactionComponent.class);
		return factionComponent != null && Faction.SETTLEMENT.equals(factionComponent.getFaction()) && isBadlyWounded(entity);
	}

	public static boolean isBadlyWounded(Entity entity) {
		if (!(entity.getPhysicalEntityComponent().getAttributes() instanceof CreatureEntityAttributes attributes)) {
			return false;
		}
		for (var entry : attributes.getBody().getAllDamage()) {
			BodyPartDamage damage = entry.getValue();
			if (damage.getDamageLevel() == BodyPartDamageLevel.Bleeding || damage.getDamageLevel() == BodyPartDamageLevel.BrokenBones) {
				return true;
			}
		}
		return false;
	}
}
