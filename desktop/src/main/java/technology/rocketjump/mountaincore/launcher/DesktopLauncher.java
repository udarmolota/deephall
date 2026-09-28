package technology.rocketjump.mountaincore.launcher;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.MountaincoreApplicationAdapter;
import technology.rocketjump.mountaincore.guice.MountaincoreGuiceModule;
import technology.rocketjump.mountaincore.logging.CrashHandler;
import technology.rocketjump.mountaincore.modding.LocalModRepository;
import technology.rocketjump.mountaincore.persistence.UserPreferences;
import technology.rocketjump.mountaincore.rendering.camera.DisplaySettings;
import technology.rocketjump.mountaincore.screens.menus.Resolution;

import java.nio.charset.Charset;

import static technology.rocketjump.mountaincore.persistence.UserPreferences.FullscreenMode.BORDERLESS_FULLSCREEN;
import static technology.rocketjump.mountaincore.persistence.UserPreferences.FullscreenMode.WINDOWED;
import static technology.rocketjump.mountaincore.persistence.UserPreferences.PreferenceKey.DISPLAY_RESOLUTION;
import static technology.rocketjump.mountaincore.screens.menus.options.GraphicsOptionsTab.getFullscreenMode;

public class DesktopLauncher {

    /**
     * A phone gives the game one surface to draw on and no OpenAL. There is no second window
     * for the game to move into once the splash screen has had its moment, and the audio
     * device would fail to open before anything is drawn at all. With
     * -Dmountaincore.android=true the game goes straight into a single silent window instead.
     * Nothing about the desktop launch changes.
     */
    private static final boolean SINGLE_SILENT_WINDOW = Boolean.getBoolean("mountaincore.android");

    public static void main(String[] args) {
        try {
            checkDefaultCharset();
            if (SINGLE_SILENT_WINDOW) {
                launchSingleWindow();
            } else {
                launchMainWindow();
            }
        } catch (Throwable e) {
            Logger.error(e);
            CrashHandler.logCrash(e);
            System.exit(-1);
        }
    }

    private static void launchSingleWindow() {
        Injector preInjector = Guice.createInjector(new MountaincoreGuiceModule());
        UserPreferences userPreferences = preInjector.getInstance(UserPreferences.class);
        preInjector.getInstance(LocalModRepository.class).packageActiveMods();

        Resolution displayResolution = getDisplayResolution(userPreferences);

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Mountaincore");
        config.disableAudio(true);
        config.setDecorated(false);
        config.setWindowedMode(displayResolution.width, displayResolution.height);

        new Lwjgl3Application(new MountaincoreApplicationAdapter(), config);
    }

    private static void launchMainWindow() {
        ApplicationListener splashScreenListener = new ApplicationAdapter() {
            private SpriteBatch spriteBatch;
            private Texture splashTexture;
            private Lwjgl3Window splashWindow;
            private boolean requestStart = true;

            @Override
            public void create() {
                super.create();
                spriteBatch = new SpriteBatch();
                splashTexture = new Texture(Gdx.files.absolute("assets/main_menu/splash.jpg"));
                splashWindow = ((Lwjgl3Graphics)Gdx.graphics).getWindow();
            }

            @Override
            public void render() {
                super.render();
                spriteBatch.begin();
                spriteBatch.draw(splashTexture, 0, 0);
                spriteBatch.end();
                if (requestStart) {
                    requestStart = false;

                    splashWindow.postRunnable(() -> {
                        startGame((Lwjgl3Application) Gdx.app);
                        splashWindow.closeWindow();
                    });
                }
            }

            @Override
            public void dispose() {
                super.dispose();
                splashTexture.dispose();
                spriteBatch.dispose();
            }
        };
        Lwjgl3ApplicationConfiguration splashConfig = new Lwjgl3ApplicationConfiguration();
        splashConfig.setTitle("Mountaincore");
        splashConfig.setWindowedMode(600, 600);
        splashConfig.setDecorated(false);
        splashConfig.setWindowIcon("assets/icon/Steam_Icon_128x128.png", "assets/icon/Steam_Icon_32x32.png", "assets/icon/Steam_Icon_16x16.png");
        Lwjgl3Application application = new Lwjgl3Application(splashScreenListener, splashConfig);
    }

    private static void startGame(Lwjgl3Application application) {
        // config for main window
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Mountaincore");

        Injector preInjector = Guice.createInjector(new MountaincoreGuiceModule());
        UserPreferences userPreferences = preInjector.getInstance(UserPreferences.class);

        LocalModRepository localModRepository = preInjector.getInstance(LocalModRepository.class);
        localModRepository.packageActiveMods();

        UserPreferences.FullscreenMode fullscreenMode = getFullscreenMode(userPreferences);
        Resolution displayResolution = getDisplayResolution(userPreferences);
        if (fullscreenMode == WINDOWED || fullscreenMode == BORDERLESS_FULLSCREEN) {
            config.setWindowedMode(displayResolution.width, displayResolution.height);
            if (fullscreenMode == BORDERLESS_FULLSCREEN) {
                config.setDecorated(false);
            }
        } else {
            config.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());
        }

        config.setWindowIcon("assets/icon/Steam_Icon_128x128.png", "assets/icon/Steam_Icon_32x32.png", "assets/icon/Steam_Icon_16x16.png");

        MountaincoreApplicationAdapter gameInstance = new MountaincoreApplicationAdapter();
        application.newWindow(gameInstance, config);
    }


    private static Resolution getDisplayResolution(UserPreferences userPreferences) {
        Graphics.DisplayMode desktopMode = Lwjgl3ApplicationConfiguration.getDisplayMode();
        Resolution desktopResolution = new Resolution(desktopMode.width, desktopMode.height);
        String preferredResolution = userPreferences.getPreference(DISPLAY_RESOLUTION);
        Resolution resolutionToUse;
        if (preferredResolution == null) {
            userPreferences.setPreference(DISPLAY_RESOLUTION, desktopResolution.toString());
            resolutionToUse = desktopResolution;
        } else {
            try {
                resolutionToUse = Resolution.byString(preferredResolution);
            } catch (NumberFormatException e) {
                Logger.error("Could not parse " + DISPLAY_RESOLUTION.name() + " preference: " + preferredResolution);
                resolutionToUse = desktopResolution;
            }
            if (resolutionToUse.width > desktopResolution.width || resolutionToUse.height > desktopResolution.height) {
                // A resolution larger than the screen leaves the window, and with it the
                // options that would undo the choice, off the edge of the display. The
                // preference lives outside the game directory, so reinstalling does not
                // help either: the player is simply stuck. Fall back to what fits and
                // remember that, so the options screen shows the truth.
                Logger.warn("Saved resolution " + resolutionToUse + " does not fit the display " + desktopResolution + ", falling back");
                resolutionToUse = desktopResolution;
                userPreferences.setPreference(DISPLAY_RESOLUTION, desktopResolution.toString());
            }
        }
        DisplaySettings.currentResolution = resolutionToUse;
        return resolutionToUse;
    }

	private static void checkDefaultCharset() {
        Logger.info("Default character set is " + Charset.defaultCharset().name());
	}

}
