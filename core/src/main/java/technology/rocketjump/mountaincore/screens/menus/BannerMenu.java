package technology.rocketjump.mountaincore.screens.menus;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.ui.i18n.I18nTranslator;
import technology.rocketjump.mountaincore.ui.skins.GuiSkinRepository;
import technology.rocketjump.mountaincore.ui.widgets.MenuButtonFactory;

public abstract class BannerMenu implements Menu {
    protected final Skin menuSkin;
    protected final MenuButtonFactory menuButtonFactory;
    protected final MessageDispatcher messageDispatcher;
    protected final Image bannerPoleImage;
    protected final I18nTranslator i18nTranslator;

    private final Stack sceneStack = new Stack();


    public BannerMenu(GuiSkinRepository skinRepository, MenuButtonFactory menuButtonFactory, MessageDispatcher messageDispatcher, I18nTranslator i18nTranslator) {
        this.menuSkin = skinRepository.getMenuSkin();
        this.menuButtonFactory = menuButtonFactory;
        this.messageDispatcher = messageDispatcher;
        this.i18nTranslator = i18nTranslator;
        this.bannerPoleImage = new Image(menuSkin.getDrawable("asset_bg_banner_pole"), Scaling.fit); //TODO: Decide on what to do about the pole

        bannerPoleImage.setAlign(Align.top);

    }

    @Override
    public void show() {

    }

    @Override
    public void hide() {

    }

    @Override
    public void populate(Table containerTable) {
        containerTable.add(sceneStack).expand().fill();
        savedGamesUpdated();
    }

    @Override
    public void reset() {
    }

    public abstract void savedGamesUpdated();

    private Actor buildMainMenuLayer() {
        //main banner
        Table mainBanner = new Table();
        mainBanner.background(menuSkin.getDrawable("asset_bg_banner"));

        Table mainBannerComponents = new Table();
        addMainBannerComponents(mainBannerComponents);
        mainBanner.add(getMainBannerLogo()).top().height(522f).width(576f).row();
        mainBanner.add(mainBannerComponents).top().expand().fillX();

        //secondary/side banner
        Table secondaryBanner = new Table();
        secondaryBanner.defaults().maxWidth(576f);
        secondaryBanner.setBackground(menuSkin.getDrawable("asset_bg_options_banner_rolled"));

        float secondaryBannerPadTop = 0f;
        String secondaryBannerTitleI18nKey = getSecondaryBannerTitleI18nKey();
        if (secondaryBannerTitleI18nKey != null) {
            Label secondaryBannerTitle = new Label(i18nTranslator.translate(secondaryBannerTitleI18nKey), menuSkin, "secondary_banner_title");
            secondaryBannerTitle.setAlignment(Align.center);
            secondaryBanner.add(secondaryBannerTitle).top().padTop(110f).row();
        }

        Table secondaryBannerComponents = new Table();
        secondaryBannerComponents.defaults().maxWidth(576f);
        addSecondaryBannerComponents(secondaryBannerComponents);
        if (!secondaryBannerComponents.getChildren().isEmpty()) {
            secondaryBannerPadTop = 32f; //this deals with the asset_secondary_banner_bg not having same padding baked in asset like rolled is
            secondaryBanner.setBackground(menuSkin.getDrawable("asset_secondary_banner_bg"));
            secondaryBanner.add(secondaryBannerComponents).top().padTop(258f).expand().fillX();
        }


        Table positioningTable = new Table();
        positioningTable.right().top();
        positioningTable.padRight(230f);
        positioningTable.add(secondaryBanner).width(644f).top().padTop(secondaryBannerPadTop).padRight(128f);
        positioningTable.add(mainBanner).padTop(32f);
        return positioningTable;
    }

    protected String getSecondaryBannerTitleI18nKey() {
        return null;
    }

    protected abstract void addSecondaryBannerComponents(Table secondaryBanner);

    protected abstract Actor getMainBannerLogo();

    protected abstract void addMainBannerComponents(Table mainBanner);

    protected void disableButton(Container<TextButton> button) {
        button.addAction(Actions.alpha(0.5f));
        button.getActor().setDisabled(true);
        button.getActor().setTouchable(Touchable.disabled);
    }

    protected void enableButton(Container<TextButton> button) {
        button.clearActions();
        button.addAction(Actions.alpha(1f));
        button.getActor().setDisabled(false);
        button.getActor().setTouchable(Touchable.enabled);
    }

    public void rebuild() {
        sceneStack.clear();
        sceneStack.add(bannerPoleImage);
        sceneStack.add(buildMainMenuLayer());
    }

    @Override
    public boolean showVersionDetails() {
        return false; // Hiding version on main menu now the game has stopped being updated, set this to true to show
    }
}
