package technology.rocketjump.mountaincore.entities.behaviour.furniture;

import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;

public class TradingImportFurnitureBehaviour extends ProductionExportFurnitureBehaviour {

	// A merchant retries a sale often while trading, so a recent failure means the depot cannot pay now
	private static final double HOURS_SHORT_OF_PAYMENT_SHOWN = 2.0;

	private Double lastShortOfPaymentGameTime;

	public void paymentNotFound(double currentGameTime) {
		lastShortOfPaymentGameTime = currentGameTime;
	}

	public void paymentFound() {
		lastShortOfPaymentGameTime = null;
	}

	public boolean isShortOfPayment(double currentGameTime) {
		return lastShortOfPaymentGameTime != null && currentGameTime - lastShortOfPaymentGameTime < HOURS_SHORT_OF_PAYMENT_SHOWN;
	}

	@Override
	public FurnitureBehaviour clone(MessageDispatcher messageDispatcher, GameContext gameContext) {
		TradingImportFurnitureBehaviour cloned = new TradingImportFurnitureBehaviour();
		cloned.maxNumItemStacks = this.maxNumItemStacks;
		cloned.selectedItemType = this.selectedItemType;
		cloned.selectedMaterial = this.selectedMaterial;
		cloned.pendingAssignments.addAll(this.pendingAssignments);
		return cloned;
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
		super.writeTo(asJson, savedGameStateHolder);
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		super.readFrom(asJson, savedGameStateHolder, relatedStores);
	}
}
