package technology.rocketjump.mountaincore.ui;

import com.badlogic.gdx.math.Vector2;
import technology.rocketjump.mountaincore.persistence.UserPreferences;

public class ViewportUtils {

    // Lower means a bigger interface. Below 0.9 is for small screens, where the 4K layout is tiny
    public static final float MIN_VIEWPORT_SCALE = 0.6f;
    private static final float MIN_FONT_SCALED_VIEWPORT = 0.9f;
    public static final float MAX_VIEWPORT_SCALE = 1.15f;
    private final static Vector2 VIEWPORT_DIMENSIONS = new Vector2(3840, 2160);
    public static final float DEFAULT_FONT_SCALE = 2.0f;
    public static final float MAX_DEFAULT_FONT_SCALE = 2.6f;

    public static Vector2 scaledViewportDimensions(UserPreferences userPreferences) {
        return VIEWPORT_DIMENSIONS.cpy().scl(getViewportScale(userPreferences));
    }

    public static float defaultFontScaleForViewportScale(UserPreferences userPreferences) {
        //calculation for the defaultFontScale
        float maxV = ViewportUtils.MAX_VIEWPORT_SCALE;
        float minV = ViewportUtils.MIN_FONT_SCALED_VIEWPORT;
        float midpoint = minV + ((maxV - minV)/2.0f);
        float diffFromMidpoint = getViewportScale(userPreferences) - midpoint;

        final float percentAlongViewportDomain;
        if (diffFromMidpoint < 0) {
            percentAlongViewportDomain = Math.abs(diffFromMidpoint) / (midpoint - minV);
        } else {
            percentAlongViewportDomain = diffFromMidpoint / (maxV - midpoint);
        }
        return DEFAULT_FONT_SCALE + (Math.min(percentAlongViewportDomain, 1f) * (MAX_DEFAULT_FONT_SCALE - DEFAULT_FONT_SCALE));
    }

    private static Float getViewportScale(UserPreferences userPreferences) {
        String preference = userPreferences.getPreference(UserPreferences.PreferenceKey.UI_SCALE);
        return Float.valueOf(preference);
    }
}
