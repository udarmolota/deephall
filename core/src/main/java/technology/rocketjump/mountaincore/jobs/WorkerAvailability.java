package technology.rocketjump.mountaincore.jobs;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import technology.rocketjump.mountaincore.entities.components.InventoryComponent;
import technology.rocketjump.mountaincore.entities.components.creature.MilitaryComponent;
import technology.rocketjump.mountaincore.entities.components.creature.SkillsComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.GameContextAware;
import technology.rocketjump.mountaincore.jobs.model.Job;
import technology.rocketjump.mountaincore.jobs.model.Skill;
import technology.rocketjump.mountaincore.materials.model.GameMaterial;
import technology.rocketjump.mountaincore.settlement.SettlementItemTracker;
import technology.rocketjump.mountaincore.settlement.SettlerTracker;
import technology.rocketjump.mountaincore.settlement.notifications.NotificationType;
import technology.rocketjump.mountaincore.ui.i18n.I18nString;
import technology.rocketjump.mountaincore.ui.i18n.I18nText;
import technology.rocketjump.mountaincore.ui.i18n.I18nTranslator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Works out why nobody can take a job, so the player can be told instead of the job silently waiting.
 */
@Singleton
public class WorkerAvailability implements GameContextAware {

	private static final String TOOL_NAME_PREFIX = "Tool-";

	public enum Problem {

		NO_WORKER("WORKER_SHORTAGE.NO_WORKER", NotificationType.WORKER_MISSING),
		NO_TOOL("WORKER_SHORTAGE.NO_TOOL", NotificationType.TOOL_MISSING);

		public final String i18nKey;
		public final NotificationType notificationType;

		Problem(String i18nKey, NotificationType notificationType) {
			this.i18nKey = i18nKey;
			this.notificationType = notificationType;
		}
	}

	public record Shortage(Problem problem, Skill profession, ItemType tool) {}

	private final SettlerTracker settlerTracker;
	private final SettlementItemTracker settlementItemTracker;
	private GameContext gameContext;

	@Inject
	public WorkerAvailability(SettlerTracker settlerTracker, SettlementItemTracker settlementItemTracker) {
		this.settlerTracker = settlerTracker;
		this.settlementItemTracker = settlementItemTracker;
	}

	public Optional<Shortage> check(Job job) {
		ItemType tool = job.getRequiredItemType();
		if (tool == null || !tool.getItemTypeName().startsWith(TOOL_NAME_PREFIX) || usesWorkstationTool(job)) {
			tool = null;
		}
		return check(job.getRequiredProfession(), tool, job.getRequiredItemMaterial());
	}

	public Optional<Shortage> check(Skill profession, ItemType tool, GameMaterial toolMaterial) {
		if (profession == null || profession == SkillDictionary.NULL_PROFESSION
				|| profession == SkillDictionary.CONTEXT_DEPENDENT_PROFESSION_REQUIRED) {
			return Optional.empty();
		}

		List<Entity> civilianWorkers = settlerTracker.getLiving().stream()
				.filter(settler -> hasProfession(settler, profession) && !isInMilitary(settler))
				.toList();

		if (civilianWorkers.isEmpty()) {
			return Optional.of(new Shortage(Problem.NO_WORKER, profession, null));
		}
		if (tool != null && !toolIsAvailable(civilianWorkers, tool, toolMaterial)) {
			return Optional.of(new Shortage(Problem.NO_TOOL, profession, tool));
		}
		return Optional.empty();
	}

	public I18nText describe(Shortage shortage, I18nTranslator i18nTranslator) {
		return i18nTranslator.getTranslatedWordWithReplacements(shortage.problem().i18nKey, textReplacements(shortage, i18nTranslator));
	}

	public Map<String, I18nString> textReplacements(Shortage shortage, I18nTranslator i18nTranslator) {
		Map<String, I18nString> replacements = new HashMap<>();
		replacements.put("profession", i18nTranslator.getWord(shortage.profession().getI18nKey()));
		if (shortage.tool() != null) {
			replacements.put("tool", i18nTranslator.getWord(shortage.tool().getI18nKey()));
		}
		return replacements;
	}

	private boolean hasProfession(Entity settler, Skill profession) {
		SkillsComponent skillsComponent = settler.getComponent(SkillsComponent.class);
		return skillsComponent != null && skillsComponent.hasActiveProfession(profession);
	}

	private boolean isInMilitary(Entity settler) {
		MilitaryComponent militaryComponent = settler.getComponent(MilitaryComponent.class);
		return militaryComponent != null && militaryComponent.isInMilitary();
	}

	private boolean toolIsAvailable(List<Entity> workers, ItemType tool, GameMaterial toolMaterial) {
		for (Entity worker : workers) {
			InventoryComponent inventoryComponent = worker.getComponent(InventoryComponent.class);
			if (inventoryComponent != null && (toolMaterial != null
					? inventoryComponent.findByItemTypeAndMaterial(tool, toolMaterial, gameContext.getGameClock())
					: inventoryComponent.findByItemType(tool, gameContext.getGameClock())) != null) {
				return true;
			}
		}
		List<Entity> freeTools = toolMaterial != null
				? settlementItemTracker.getItemsByTypeAndMaterial(tool, toolMaterial, true)
				: settlementItemTracker.getItemsByType(tool, true);
		return !freeTools.isEmpty();
	}

	private boolean usesWorkstationTool(Job job) {
		if (job.getCraftingRecipe() != null) {
			return job.getCraftingRecipe().getCraftingType().isUsesWorkstationTool();
		} else {
			return job.getType().isUsesWorkstationTool();
		}
	}

	@Override
	public void onContextChange(GameContext gameContext) {
		this.gameContext = gameContext;
	}

	@Override
	public void clearContextRelatedState() {

	}
}
