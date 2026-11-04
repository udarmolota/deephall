package technology.rocketjump.mountaincore.settlement;

import technology.rocketjump.mountaincore.entities.components.Faction;
import technology.rocketjump.mountaincore.entities.components.FactionComponent;
import technology.rocketjump.mountaincore.entities.components.InventoryComponent;
import technology.rocketjump.mountaincore.entities.components.creature.MilitaryComponent;
import technology.rocketjump.mountaincore.entities.components.creature.SkillsComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.combat.WeaponInfo;
import technology.rocketjump.mountaincore.entities.model.physical.creature.Consciousness;
import technology.rocketjump.mountaincore.entities.model.physical.creature.CreatureEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.creature.EquippedItemComponent;
import technology.rocketjump.mountaincore.entities.model.physical.item.AmmoType;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.gamecontext.GameContext;

import java.util.List;
import java.util.Objects;

/**
 * Who hunts and with what. A hunter is a civilian with the HUNTER profession; the bow or crossbow is assigned
 * to them like a soldier's weapon (MilitaryComponent's assigned weapon), so fetching it, equipping it in
 * combat and topping up its ammunition all work the way they do for soldiers.
 */
public final class HuntingRules {

	public static final String HUNTER_PROFESSION_NAME = "HUNTER";

	private HuntingRules() {
	}

	/** A soldier is not a hunter even with the profession: the squad decides what a soldier attacks. */
	public static boolean isHunter(Entity entity) {
		SkillsComponent skillsComponent = entity.getComponent(SkillsComponent.class);
		if (skillsComponent == null) {
			return false;
		}
		MilitaryComponent militaryComponent = entity.getComponent(MilitaryComponent.class);
		if (militaryComponent != null && militaryComponent.isInMilitary()) {
			return false;
		}
		return skillsComponent.getActiveProfessions().stream()
				.anyMatch(profession -> profession.getSkill().getName().equals(HUNTER_PROFESSION_NAME));
	}

	/** The hunter's assigned weapon if it is one that shoots: a bow or a crossbow. */
	public static Entity getHuntingWeapon(Entity hunter, GameContext gameContext) {
		MilitaryComponent militaryComponent = hunter.getComponent(MilitaryComponent.class);
		if (militaryComponent == null || militaryComponent.getAssignedWeaponId() == null) {
			return null;
		}
		Entity weapon = gameContext.getEntities().get(militaryComponent.getAssignedWeaponId());
		WeaponInfo weaponInfo = getWeaponInfo(weapon);
		return weaponInfo != null && weaponInfo.getRequiresAmmoType() != null ? weapon : null;
	}

	/** The hunting weapon is with the hunter: carried, or in hand during a fight. */
	public static boolean hasHuntingWeaponOnPerson(Entity hunter, Entity weapon) {
		InventoryComponent inventoryComponent = hunter.getComponent(InventoryComponent.class);
		if (inventoryComponent != null && inventoryComponent.getById(weapon.getId()) != null) {
			return true;
		}
		EquippedItemComponent equippedItemComponent = hunter.getComponent(EquippedItemComponent.class);
		return equippedItemComponent != null && equippedItemComponent.getMainHandItem() != null &&
				equippedItemComponent.getMainHandItem().getId() == weapon.getId();
	}

	public static int ammoCarried(Entity hunter, Entity weapon) {
		AmmoType ammoType = getWeaponInfo(weapon).getRequiresAmmoType();
		InventoryComponent inventoryComponent = hunter.getComponent(InventoryComponent.class);
		if (inventoryComponent == null) {
			return 0;
		}
		int amount = 0;
		for (InventoryComponent.InventoryEntry entry : inventoryComponent.getInventoryEntries()) {
			if (entry.entity.getPhysicalEntityComponent().getAttributes() instanceof ItemEntityAttributes attributes &&
					ammoType.equals(attributes.getItemType().getIsAmmoType())) {
				amount += attributes.getQuantity();
			}
		}
		return amount;
	}

	/** Armed, with the weapon on them and something to shoot. */
	public static boolean isReadyToHunt(Entity hunter, GameContext gameContext) {
		if (!isHunter(hunter)) {
			return false;
		}
		Entity weapon = getHuntingWeapon(hunter, gameContext);
		return weapon != null && hasHuntingWeaponOnPerson(hunter, weapon) && ammoCarried(hunter, weapon) > 0;
	}

	/**
	 * In a fight, a hunter stands and shoots only at a wild animal, and only with a bow or crossbow assigned.
	 * Against invaders or monsters, or unarmed, a hunter is a civilian like any other and flees.
	 */
	public static boolean fightsAsHunter(Entity hunter, Entity opponent, GameContext gameContext) {
		return opponent != null && isHunter(hunter) && getHuntingWeapon(hunter, gameContext) != null &&
				isWildAnimal(opponent);
	}

	public static List<Entity> livingHuntTargets(GameContext gameContext) {
		return gameContext.getSettlementState().getHuntTargetEntityIds().stream()
				.map(id -> gameContext.getEntities().get(id))
				.filter(Objects::nonNull)
				.filter(HuntingRules::isAlive)
				.toList();
	}

	public static boolean isWildAnimal(Entity entity) {
		return entity.getOrCreateComponent(FactionComponent.class).getFaction().equals(Faction.WILD_ANIMALS);
	}

	private static boolean isAlive(Entity entity) {
		return entity.getPhysicalEntityComponent().getAttributes() instanceof CreatureEntityAttributes attributes &&
				!attributes.getConsciousness().equals(Consciousness.DEAD);
	}

	private static WeaponInfo getWeaponInfo(Entity item) {
		if (item != null && item.getPhysicalEntityComponent().getAttributes() instanceof ItemEntityAttributes attributes) {
			return attributes.getItemType().getWeaponInfo();
		}
		return null;
	}
}
