package technology.rocketjump.mountaincore.ui.widgets;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import technology.rocketjump.mountaincore.persistence.UserPreferences;
import technology.rocketjump.mountaincore.ui.ViewportUtils;

/**
 * On a small screen the headers and icon rows of windows take up room the content needs.
 * These shrink them there, and leave larger screens as they were designed.
 */
@Singleton
public class CompactLayout {

	private static final float HEADER_SCALE = 0.5f;
	private static final float ICON_SCALE = 1f / 1.5f;

	private final UserPreferences userPreferences;

	@Inject
	public CompactLayout(UserPreferences userPreferences) {
		this.userPreferences = userPreferences;
	}

	public <T extends Label> Cell<T> header(Cell<T> cell) {
		if (ViewportUtils.isSmallScreenScale(userPreferences)) {
			Label label = cell.getActor();
			float originalHeight = label.getPrefHeight();
			label.setFontScale(HEADER_SCALE);
			cell.height(originalHeight * HEADER_SCALE);
			cell.padTop(cell.getPadTop() * HEADER_SCALE);
			cell.padBottom(cell.getPadBottom() * HEADER_SCALE);
		}
		return cell;
	}

	public <T extends Actor> Cell<T> icon(Cell<T> cell) {
		if (ViewportUtils.isSmallScreenScale(userPreferences) && cell.getActor() instanceof Layout layout) {
			cell.size(layout.getPrefWidth() * ICON_SCALE, layout.getPrefHeight() * ICON_SCALE);
		}
		return cell;
	}
}
