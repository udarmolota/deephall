package technology.rocketjump.mountaincore.ui.views;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.gamecontext.GameContextAware;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.settlement.notifications.Notification;
import technology.rocketjump.mountaincore.ui.i18n.I18nString;
import technology.rocketjump.mountaincore.ui.i18n.I18nTranslator;
import technology.rocketjump.mountaincore.ui.skins.GuiSkinRepository;

import java.util.Map;

/**
 * A few small lines under the top of the screen telling what just happened in the settlement.
 * Each line fades away on its own; nothing here can be touched, so the map underneath still works.
 */
@Singleton
public class EventFeedGuiView implements Telegraph, GameContextAware {

	private static final int MAX_LINES = 4;
	private static final float SECONDS_SHOWN = 30f;
	private static final float FADE_SECONDS = 2f;
	// The free strip below the speed buttons, from the middle of the screen to the clock
	private static final float LEFT_EDGE_OF_SCREEN_WIDTH = 0.48f;
	private static final float TOP_EDGE_OF_SCREEN_HEIGHT = 0.11f;

	private final I18nTranslator i18nTranslator;
	private final Label.LabelStyle lineStyle;
	private final Drawable lineBackground;
	private final VerticalGroup lines = new VerticalGroup();

	@Inject
	public EventFeedGuiView(MessageDispatcher messageDispatcher, I18nTranslator i18nTranslator, GuiSkinRepository guiSkinRepository) {
		this.i18nTranslator = i18nTranslator;
		Skin managementSkin = guiSkinRepository.getManagementSkin();
		this.lineStyle = managementSkin.get("default-font-16-label-white", Label.LabelStyle.class);
		this.lineBackground = managementSkin.newDrawable("white-pixel", new Color(0f, 0f, 0f, 0.45f));

		lines.columnLeft().left().space(4f);
		lines.setTouchable(Touchable.disabled);

		messageDispatcher.addListener(this, MessageType.POST_NOTIFICATION);
	}

	public void populate(Table containerTable) {
		containerTable.top().left();
		containerTable.add(lines).top().left()
				.padTop(Value.percentHeight(TOP_EDGE_OF_SCREEN_HEIGHT, containerTable))
				.padLeft(Value.percentWidth(LEFT_EDGE_OF_SCREEN_WIDTH, containerTable));
	}

	@Override
	public boolean handleMessage(Telegram msg) {
		if (msg.message == MessageType.POST_NOTIFICATION && msg.extraInfo instanceof Notification notification) {
			addLine(describe(notification));
		}
		// Other listeners show the same notification in their own way
		return false;
	}

	private String describe(Notification notification) {
		String text = i18nTranslator.translate(notification.getType().getI18nTitleKey());
		for (Map.Entry<String, I18nString> replacement : notification.getTextReplacements()) {
			if ("character".equals(replacement.getKey())) {
				text = text + ": " + replacement.getValue().toString();
			}
		}
		return text;
	}

	private void addLine(String text) {
		while (lines.getChildren().size >= MAX_LINES) {
			lines.getChildren().first().remove();
		}

		Label label = new Label(text, lineStyle);
		Container<Label> line = new Container<>(label);
		line.setBackground(lineBackground);
		line.pad(4f, 12f, 4f, 12f);
		line.setTouchable(Touchable.disabled);
		line.addAction(Actions.sequence(
				Actions.delay(SECONDS_SHOWN - FADE_SECONDS),
				Actions.fadeOut(FADE_SECONDS),
				Actions.removeActor()));
		lines.addActor(line);
	}

	@Override
	public void onContextChange(GameContext gameContext) {

	}

	@Override
	public void clearContextRelatedState() {
		lines.clearChildren();
	}
}
