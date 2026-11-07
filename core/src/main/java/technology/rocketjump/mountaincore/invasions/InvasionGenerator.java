package technology.rocketjump.mountaincore.invasions;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.math.Vector2;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.assets.entities.model.EntityAssetOrientation;
import technology.rocketjump.mountaincore.entities.SequentialIdGenerator;
import technology.rocketjump.mountaincore.entities.ai.goap.EntityNeed;
import technology.rocketjump.mountaincore.entities.behaviour.creature.CreatureBehaviour;
import technology.rocketjump.mountaincore.entities.behaviour.creature.InvasionCreatureGroup;
import technology.rocketjump.mountaincore.entities.components.Faction;
import technology.rocketjump.mountaincore.entities.components.InventoryComponent;
import technology.rocketjump.mountaincore.entities.components.creature.MilitaryComponent;
import technology.rocketjump.mountaincore.entities.components.creature.NeedsComponent;
import technology.rocketjump.mountaincore.entities.components.creature.SkillsComponent;
import technology.rocketjump.mountaincore.entities.factories.CreatureEntityAttributesFactory;
import technology.rocketjump.mountaincore.entities.factories.CreatureEntityFactory;
import technology.rocketjump.mountaincore.entities.factories.ItemEntityAttributesFactory;
import technology.rocketjump.mountaincore.entities.factories.ItemEntityFactory;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.creature.CreatureEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.creature.EquippedItemComponent;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemQuality;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemTypeDictionary;
import technology.rocketjump.mountaincore.entities.model.physical.item.QuantifiedItemTypeWithMaterial;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.GameContextAware;
import technology.rocketjump.mountaincore.invasions.model.InvasionDefinition;
import technology.rocketjump.mountaincore.invasions.model.InvasionEquipmentDescriptor;
import technology.rocketjump.mountaincore.invasions.model.InvasionParticipant;
import technology.rocketjump.mountaincore.jobs.SkillDictionary;
import technology.rocketjump.mountaincore.materials.GameMaterialDictionary;
import technology.rocketjump.mountaincore.materials.model.GameMaterial;
import technology.rocketjump.mountaincore.materials.model.GameMaterialType;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.misc.VectorUtils;

import java.util.*;

@Singleton
public class InvasionGenerator implements GameContextAware {

	private static final int MIN_WEAPON_SKILL = 40;
	private static final int MAX_WEAPON_SKILL = 65;
	private final MessageDispatcher messageDispatcher;
	private final ItemEntityAttributesFactory itemEntityAttributesFactory;
	private final ItemEntityFactory itemEntityFactory;
	private final CreatureEntityAttributesFactory creatureEntityAttributesFactory;
	private final CreatureEntityFactory creatureEntityFactory;
	private final GameMaterialDictionary materialDictionary;
	private GameContext gameContext;
	private final ItemTypeDictionary itemTypeDictionary;

	// Every third invader of a raid is an archer (Inna: an archer only once there are more than two): a raid
	// of 1-2 has none, 3-5 has one, 6-8 two
	private static final int ARCHER_EVERY_NTH_INVADER = 3;
	private static final int AMMO_PER_ARCHER = 20;

	@Inject
	public InvasionGenerator(MessageDispatcher messageDispatcher, ItemEntityAttributesFactory itemEntityAttributesFactory,
							 ItemEntityFactory itemEntityFactory, CreatureEntityAttributesFactory creatureEntityAttributesFactory, CreatureEntityFactory creatureEntityFactory, GameMaterialDictionary materialDictionary,
							 ItemTypeDictionary itemTypeDictionary) {
		this.itemTypeDictionary = itemTypeDictionary;
		this.messageDispatcher = messageDispatcher;
		this.itemEntityAttributesFactory = itemEntityAttributesFactory;

		this.itemEntityFactory = itemEntityFactory;
		this.creatureEntityAttributesFactory = creatureEntityAttributesFactory;
		this.creatureEntityFactory = creatureEntityFactory;
		this.materialDictionary = materialDictionary;
	}

	public List<Entity> generateInvasionParticipants(InvasionDefinition definition, Vector2 invasionLocation, int pointsBudget) {
		List<Entity> participants = new ArrayList<>();
		Random random = gameContext.getRandom();
		Map<GameMaterialType, GameMaterial> invasionMaterials = new HashMap();


		InvasionCreatureGroup group = new InvasionCreatureGroup();
		group.setGroupId(SequentialIdGenerator.nextId());
		group.setHomeLocation(VectorUtils.toGridPoint(invasionLocation));
		group.setVictoryPointsTarget(pointsBudget);
		group.setInvasionDefinition(definition);

		int pointsSpent = 0;
		int invaderNumber = 0;
		while (pointsSpent < pointsBudget) {
			invaderNumber++;
			InvasionParticipant participant = definition.getParticipants().get(random.nextInt(definition.getParticipants().size()));

			CreatureEntityAttributes creatureAttributes = creatureEntityAttributesFactory.create(participant.getRace());
			pointsSpent += participant.getBasePointsCost();
			Entity invader = creatureEntityFactory.create(creatureAttributes, invasionLocation, EntityAssetOrientation.DOWN.toVector2(), gameContext, Faction.HOSTILE_INVASION);
			if (invader.getBehaviourComponent() instanceof CreatureBehaviour creatureBehaviour) {
				creatureBehaviour.setCreatureGroup(group);
			}

			InventoryComponent inventoryComponent = invader.getOrCreateComponent(InventoryComponent.class);
			MilitaryComponent militaryComponent = new MilitaryComponent();
			militaryComponent.init(invader, messageDispatcher, gameContext);
			invader.addComponent(militaryComponent);
			EquippedItemComponent equippedItemComponent = invader.getOrCreateComponent(EquippedItemComponent.class);
			SkillsComponent skillsComponent = invader.getOrCreateComponent(SkillsComponent.class);
			skillsComponent.activateProfession(SkillDictionary.NULL_PROFESSION);
			NeedsComponent needsComponent = new NeedsComponent(List.of(EntityNeed.SLEEP, EntityNeed.FOOD), gameContext.getRandom());
			invader.addComponent(needsComponent);

			for (QuantifiedItemTypeWithMaterial inventoryEntry : participant.getFixedInventory()) {
				ItemEntityAttributes inventoryItemAttributes = itemEntityAttributesFactory.createItemAttributes(inventoryEntry.getItemType(), inventoryEntry.getQuantity(), inventoryEntry.getMaterial());
				if (!inventoryEntry.getItemType().isStackable()) {
					inventoryItemAttributes.setItemQuality(pickQuality(participant.getItemQualities()));
				}
				Entity inventoryEntity = itemEntityFactory.create(inventoryItemAttributes, null, true, gameContext, Faction.HOSTILE_INVASION);
				inventoryComponent.add(inventoryEntity, invader, messageDispatcher, gameContext.getGameClock());
			}

			InvasionEquipmentDescriptor weaponOption = pickWeapon(participant.getEquipmentOptions().getWeapons(),
					invaderNumber % ARCHER_EVERY_NTH_INVADER == 0, random);
			Entity weaponEntity = null;
			if (!weaponOption.isNone()) {
				ItemQuality quality = pickQuality(participant.getItemQualities());
				int itemPointCost = Math.round(quality.combatMultiplier * (float)weaponOption.getStandardPointsCost());
				pointsSpent += itemPointCost;
				weaponEntity = buildEquipment(weaponOption, quality, invasionMaterials);
				if (weaponEntity != null) {
					militaryComponent.setAssignedWeaponId(weaponEntity.getId());
					inventoryComponent.add(weaponEntity, invader, messageDispatcher, gameContext.getGameClock());
					skillsComponent.setSkillLevel(weaponOption.getItemType().getWeaponInfo().getCombatSkill(),
							MIN_WEAPON_SKILL + gameContext.getRandom().nextInt(MAX_WEAPON_SKILL - MIN_WEAPON_SKILL));
					giveAmmo(weaponOption.getItemType(), invader, inventoryComponent);
				}
			} else {
				skillsComponent.setSkillLevel(participant.getRace().getFeatures().getUnarmedWeapon().getCombatSkill(),
						MIN_WEAPON_SKILL + gameContext.getRandom().nextInt(MAX_WEAPON_SKILL - MIN_WEAPON_SKILL));
			}

			InvasionEquipmentDescriptor shieldOption = participant.getEquipmentOptions().getShield().get(random.nextInt(participant.getEquipmentOptions().getShield().size()));
			boolean weaponIsTwoHanded = false;
			if (weaponEntity != null) {
				ItemEntityAttributes weaponAttributes = (ItemEntityAttributes) weaponEntity.getPhysicalEntityComponent().getAttributes();
				weaponIsTwoHanded = weaponAttributes.getItemType().getWeaponInfo().isTwoHanded();
			}
			if (!shieldOption.isNone() && !weaponIsTwoHanded) {
				ItemQuality quality = pickQuality(participant.getItemQualities());
				int itemPointCost = Math.round(quality.combatMultiplier * (float)shieldOption.getStandardPointsCost());
				pointsSpent += itemPointCost;
				Entity shieldEntity = buildEquipment(shieldOption, quality, invasionMaterials);
				if (shieldEntity != null) {
					militaryComponent.setAssignedShieldId(shieldEntity.getId());
					inventoryComponent.add(shieldEntity, invader, messageDispatcher, gameContext.getGameClock());
				}
			}

			InvasionEquipmentDescriptor armorOption = participant.getEquipmentOptions().getArmor().get(random.nextInt(participant.getEquipmentOptions().getArmor().size()));
			if (!armorOption.isNone()) {
				ItemQuality quality = pickQuality(participant.getItemQualities());
				int itemPointCost = Math.round(quality.combatMultiplier * (float)armorOption.getStandardPointsCost());
				pointsSpent += itemPointCost;
				Entity armorEntity = buildEquipment(armorOption, quality, invasionMaterials);
				if (armorEntity != null) {
					militaryComponent.setAssignedArmorId(armorEntity.getId());
					equippedItemComponent.setEquippedClothing(armorEntity, invader, messageDispatcher);
				}
			}

			messageDispatcher.dispatchMessage(MessageType.ENTITY_ASSET_UPDATE_REQUIRED, invader);
			participants.add(invader);
		}

		if (!participants.isEmpty()) {
			messageDispatcher.dispatchMessage(4f, MessageType.INVASION_ABOUT_TO_BEGIN, participants.get(0));
		}

		return participants;
	}

	private Entity buildEquipment(InvasionEquipmentDescriptor equipmentDescriptor, ItemQuality quality, Map<GameMaterialType, GameMaterial> materials) {
		ItemType itemType = equipmentDescriptor.getItemType();
		if (itemType.isStackable()) {
			Logger.error(itemType.getItemTypeName() + " should not be stackable i.e. should have ItemQuality");
			return null;
		}

		GameMaterial primaryMaterial = materials.getOrDefault(itemType.getPrimaryMaterialType(), pickMaterial(itemType.getPrimaryMaterialType()));
		materials.put(primaryMaterial.getMaterialType(), primaryMaterial);
		ItemEntityAttributes itemAttributes = itemEntityAttributesFactory.createItemAttributes(equipmentDescriptor.getItemType(), 1, primaryMaterial);
		itemAttributes.setItemQuality(quality);

		return itemEntityFactory.create(itemAttributes, null, true, gameContext, Faction.HOSTILE_INVASION);
	}

	private static boolean isRanged(InvasionEquipmentDescriptor option) {
		return !option.isNone() && option.getItemType() != null && option.getItemType().getWeaponInfo() != null
				&& option.getItemType().getWeaponInfo().isRanged();
	}

	/** An archer gets one of the ranged weapons on the list, everyone else one of the others. */
	private InvasionEquipmentDescriptor pickWeapon(List<InvasionEquipmentDescriptor> options, boolean archer, Random random) {
		List<InvasionEquipmentDescriptor> eligible = options.stream().filter(o -> isRanged(o) == archer).toList();
		if (eligible.isEmpty()) {
			eligible = options;
		}
		return eligible.get(random.nextInt(eligible.size()));
	}

	/** A stack of the cheapest ammo the weapon fires (bone arrows for a bow), if it needs any. */
	private void giveAmmo(ItemType weaponType, Entity invader, InventoryComponent inventoryComponent) {
		if (weaponType.getWeaponInfo() == null || weaponType.getWeaponInfo().getRequiresAmmoType() == null) {
			return;
		}
		ItemType ammoType = itemTypeDictionary.getAll().stream()
				.filter(t -> weaponType.getWeaponInfo().getRequiresAmmoType().equals(t.getIsAmmoType()))
				.min(Comparator.comparingInt(ItemType::getBaseValuePerItem))
				.orElse(null);
		if (ammoType == null) {
			Logger.error("No ammo item type for " + weaponType.getItemTypeName());
			return;
		}
		GameMaterial material = pickMaterial(ammoType.getPrimaryMaterialType());
		ItemEntityAttributes ammoAttributes = itemEntityAttributesFactory.createItemAttributes(ammoType,
				Math.min(AMMO_PER_ARCHER, ammoType.getMaxStackSize()), material);
		Entity ammoEntity = itemEntityFactory.create(ammoAttributes, null, true, gameContext, Faction.HOSTILE_INVASION);
		inventoryComponent.add(ammoEntity, invader, messageDispatcher, gameContext.getGameClock());
	}

	private GameMaterial pickMaterial(GameMaterialType materialType) {
		List<GameMaterial> materials = materialDictionary.getByType(materialType).stream()
				.filter(GameMaterial::isUseInRandomGeneration)
				.toList();
		return materials.get(gameContext.getRandom().nextInt(materials.size()));
	}

	private ItemQuality pickQuality(Map<ItemQuality, Float> itemQualities) {
		float totalRoll = itemQualities.values().stream().reduce(0f, Float::sum);
		float roll = gameContext.getRandom().nextFloat() * totalRoll;

		for (Map.Entry<ItemQuality, Float> entry : itemQualities.entrySet()) {
			roll -= entry.getValue();
			if (roll <= 0) {
				return entry.getKey();
			}
		}

		return itemQualities.keySet().iterator().next();
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {

	}
}
