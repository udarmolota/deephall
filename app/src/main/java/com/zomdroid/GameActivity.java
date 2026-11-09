package com.zomdroid;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.system.ErrnoException;
import android.util.Log;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.zomdroid.input.TouchpadControlElement;
import com.zomdroid.input.GLFWBinding;
import com.zomdroid.input.GamepadManager;
import com.zomdroid.input.InputNativeInterface;
import com.zomdroid.databinding.ActivityGameBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.input.InputControlsView;
import com.zomdroid.input.KeyboardManager;


/**
 * Main game activity. Handles UI, surface, and input.
 * Integrates GamepadManager for hotplug and routes all gamepad input to the native interface.
 * Hides the virtual controller UI when a physical gamepad is connected.
 */
public class GameActivity extends AppCompatActivity implements GamepadManager.GamepadListener, KeyboardManager.KeyboardListener {
    public static final String EXTRA_GAME_INSTANCE_NAME = "com.zomdroid.GameActivity.EXTRA_GAME_INSTANCE_NAME";
    private static final String LOG_TAG = GameActivity.class.getName();

    private ActivityGameBinding binding;
    private Surface gameSurface;
    private static boolean isGameStarted = false;
    static boolean hasGameJvm() { return isGameStarted; }

    // Handles all gamepad connection/disconnection and input events
    private GamepadManager gamepadManager;
    private KeyboardManager keyboardManager;

    // Tracks whether a physical gamepad/kb is currently connected (for UI logic)
    private boolean isGamepadConnected = false;
    private boolean isKeyboardConnected = false;

    private boolean leftMouseDown  = false;
    private boolean rightMouseDown = false;
    private boolean middleMouseDown = false;
    // Pinch-to-zoom on the game surface: two fingers become mouse-wheel notches, which is
    // what the game zooms on. While a pinch is in progress single-finger mouse emulation is
    // suspended, so the second finger is not taken for a click and the spread is not a drag.
    private ScaleGestureDetector pinchDetector;
    private boolean pinching = false;
    // Two fingers moving together pan the map, which the game does with a middle-button drag.
    private boolean twoFingerPanning = false;
    private float twoFingerStartX, twoFingerStartY, twoFingerStartSpan;
    // A finger that lands does not click yet: it becomes a click when lifted, a drag when it moves,
    // and nothing at all when a second finger joins it to pan or zoom.
    private boolean fingerTapPending = false;
    private float fingerDownX, fingerDownY;

    private boolean systemKeyboardVisible = false;
    // Helps to calculate mouse cursor position
    private float renderScale = 1f;

    // Launch settings of the instance being played. Resolved from the intent extra in onCreate();
    // with a null name it reads the global values, which is the same thing it did before.
    private com.zomdroid.game.InstanceSettings instanceSettings;

    // In-game overlay (instance setting, see InstanceSettings.getHudMode): the classic counter or the
    // performance bar ported from ValDroid. Both count real presented frames (glfwSwapBuffers).
    private android.widget.TextView fpsText;            // classic "FPS: XX", top-left
    private long fpsLastCount = 0, fpsLastTimeMs = 0;
    private PerfOverlayView perfView;                   // full performance bar, top-centre
    private PerfSampler perfSampler;                    // its numbers, sampled off the UI thread
    private android.os.HandlerThread perfThread;
    private android.os.Handler perfHandler;
    private final android.os.Handler hudHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void updateTwoFingerPan(MotionEvent e) {
        float midX = (e.getX(0) + e.getX(1)) / 2f;
        float midY = (e.getY(0) + e.getY(1)) / 2f;
        if (!twoFingerPanning) {
            float slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop() * 2f;
            float span = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
            boolean moved = Math.hypot(midX - twoFingerStartX, midY - twoFingerStartY) > slop;
            if (!moved || Math.abs(span - twoFingerStartSpan) > slop) {
                return;
            }
            InputNativeInterface.sendCursorPos(twoFingerStartX * renderScale, twoFingerStartY * renderScale);
            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_WHEEL.code, true);
            twoFingerPanning = true;
        }
        InputNativeInterface.sendCursorPos(midX * renderScale, midY * renderScale);
    }

    private void endTwoFingerPan() {
        if (twoFingerPanning) {
            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_WHEEL.code, false);
            twoFingerPanning = false;
        }
    }

    @SuppressLint({"UnsafeDynamicallyLoadedCode", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No microphone permission: Zomdroid asked for it on every start for Project Zomboid's voice
        // chat, and players of Deephall, which has none, rightly asked why a game wants the microphone

        binding = ActivityGameBinding.inflate(getLayoutInflater());
        // Give focus to game surface to ensure it receives input events
        setContentView(binding.getRoot());
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // set instanceName before any inputControlsV calls
        String gameInstanceName = getIntent().getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName != null) {
            binding.inputControlsV.setInstanceName(gameInstanceName);
        }
        // Render scale and the two on-screen-control toggles belong to the instance being played.
        // The control elements never read preferences themselves — they ask the view — so handing
        // the view these two values here is the whole of it.
        instanceSettings = new com.zomdroid.game.InstanceSettings(gameInstanceName);
        binding.inputControlsV.setVibrateOnTouch(instanceSettings.isVibrateOnTouch());

        binding.gameSv.setFocusable(true);
        binding.gameSv.setFocusableInTouchMode(true);
        binding.gameSv.requestFocus();

        // Initializing the cursor calsulation pos helper
        renderScale = instanceSettings.getRenderScale();

        setUpHud(instanceSettings.getHudMode());
        setUpLoadingMessage();

        // Initialize and register GamepadManager for gamepad hotplug and input events
        try {
            gamepadManager = new GamepadManager(this, this);
            //gamepadManager.register();

            // Apply touch override based on saved preference
            boolean isTouchEnabled = instanceSettings.isTouchControlsEnabled();
            GamepadManager.setTouchOverride(isTouchEnabled);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to initialize GamepadManager", e);
            gamepadManager = null;
        }
        // Initialize KeyboardManager
        try {
            keyboardManager = new KeyboardManager(this, this);
            //keyboardManager.register();

          // Apply touch override based on saved preference
          boolean isTouchEnabled = instanceSettings.isTouchControlsEnabled();
          KeyboardManager.setTouchOverride(isTouchEnabled);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to initialize keyboardManager", Toast.LENGTH_SHORT).show();
            keyboardManager = null;
        }
        // Display on/off buttons overlay
        applyInputOverlay();
        binding.inputControlsV.setKeyboardToggleListener(() -> toggleSystemKeyboard());
        binding.inputControlsV.setRenderScale(renderScale);

        getWindow().setDecorFitsSystemWindows(false);
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        // Orientation is fixed in the manifest (screenOrientation + configChanges) rather than here.
        // Calling setRequestedOrientation() from onCreate() on a phone held in portrait recreated the
        // activity immediately, so onCreate() ran twice (it used to fire a permission request twice, and
        // Android dropped the second one). The manifest form starts the activity in landscape to begin
        // with, and configChanges keeps a rotation from tearing down the GL surface underneath us.

        //String gameInstanceName = getIntent().getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        gameInstanceName = getIntent().getStringExtra(EXTRA_GAME_INSTANCE_NAME);
        if (gameInstanceName == null)
            throw new RuntimeException("Expected game instance name to be passed as intent extra");
        GameInstance gameInstance = GameInstanceManager.requireSingleton().getInstanceByName(gameInstanceName);
        if (gameInstance == null)
            throw new RuntimeException("Game instance with name " + gameInstanceName + " not found");

        System.loadLibrary("zomdroid");

        binding.gameSv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface created.");
                renderScale = instanceSettings.getRenderScale();
                int width = (int) (binding.gameSv.getWidth() * renderScale);
                int height = (int) (binding.gameSv.getHeight() * renderScale);
                binding.gameSv.getHolder().setFixedSize(width, height);
                binding.inputControlsV.setRenderScale(renderScale);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(LOG_TAG, "Game surface changed.");
                gameSurface = binding.gameSv.getHolder().getSurface();
                //gameSurface = holder.getSurface();
                if (gameSurface == null) throw new RuntimeException();

                if (format != PixelFormat.RGBA_8888) {
                    Log.w(LOG_TAG, "Using unsupported pixel format " + format); // LIAMELUI seems like default is RGB_565
                }

                GameLauncher.setSurface(gameSurface, width, height);

                if (!isGameStarted) {
                    Thread thread = new Thread(() -> {
                        try {
                            GameLauncher.launch(gameInstance);
                        } catch (ErrnoException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    thread.start();
                    isGameStarted = true;
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(LOG_TAG, "Game surface destroyed.");
                GameLauncher.destroySurface();
            }
        });

      pinchDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
          // One wheel notch per 10% of spread, either way. The game's zoom is a stepped wheel
          // zoom, so the smooth factor has to be quantised somewhere. RimDroid uses 15%; on
          // the phone that felt one step too coarse for this game, 10% was picked by hand.
          private static final float NOTCH = 0.10f;
          private float accumulated = 0f;

          @Override
          public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
              accumulated = 0f;
              return true;
          }

          @Override
          public boolean onScale(@NonNull ScaleGestureDetector detector) {
              accumulated += detector.getScaleFactor() - 1f;
              while (accumulated > NOTCH) {
                  accumulated -= NOTCH;
                  InputNativeInterface.sendMouseScroll(0.0, 1.0);
              }
              while (accumulated < -NOTCH) {
                  accumulated += NOTCH;
                  InputNativeInterface.sendMouseScroll(0.0, -1.0);
              }
              return true;
          }
      });
      // A double-tap-and-drag must stay a double click for the game's inventory, not a zoom.
      pinchDetector.setQuickScaleEnabled(false);

      binding.gameSv.setOnTouchListener(new View.OnTouchListener() {
        //float renderScale = instanceSettings.getRenderScale();
        int activePointerId = -1;
        boolean leftPressedFinger = false;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (binding.inputControlsV != null
                    && binding.inputControlsV.getVisibility() == View.VISIBLE
                    && binding.inputControlsV.onTouchEvent(e)) {
                //Log.v("ZomdroidTouch", "inputControlsV consumed event");
                return true;
            }
            //Log.v("ZomdroidTouch", "inputControlsV did NOT consume, visibility="  + binding.inputControlsV.getVisibility());

          int action = e.getActionMasked();
          int idx = e.getActionIndex();

          // Fingers only: a real mouse or touchpad has its own wheel and buttons, handled below.
          if (!isMouseEvent(e, idx)) {
              pinchDetector.onTouchEvent(e);
              if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
                  // Second finger down: this is a pinch, not another click. Let go of the
                  // button the first finger is holding so the spread does not drag anything.
                  if (leftPressedFinger || leftMouseDown) {
                      InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                      leftPressedFinger = false;
                      leftMouseDown = false;
                  }
                  fingerTapPending = false;
                  pinching = true;
                  twoFingerPanning = false;
                  twoFingerStartX = (e.getX(0) + e.getX(1)) / 2f;
                  twoFingerStartY = (e.getY(0) + e.getY(1)) / 2f;
                  twoFingerStartSpan = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                  return true;
              }
              if (pinching) {
                  // Stay silent until every finger is up, apart from a two-finger pan.
                  if (action == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) {
                      updateTwoFingerPan(e);
                  } else if (action != MotionEvent.ACTION_MOVE) {
                      endTwoFingerPan();
                  }
                  if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                      TouchpadControlElement.restoreMouseCursor();
                      pinching = false;
                      activePointerId = -1;
                  }
                  return true;
              }
          }

          switch (action) {
              case MotionEvent.ACTION_DOWN:
              case MotionEvent.ACTION_POINTER_DOWN: {
                activePointerId = e.getPointerId(idx);
                float x = e.getX(idx), y = e.getY(idx);
                InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);

                if (!isMouseEvent(e, idx)) {
                    fingerTapPending = true;
                    fingerDownX = x;
                    fingerDownY = y;
                    return true;
                }
                leftPressedFinger = true;
                leftMouseDown = true;
                InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
                if (isMouseEvent(e, idx)) {
                    syncMouseReleaseFromMask(e.getButtonState()); // тихий релиз, press не генерим
                }
                return true;
              }
              case MotionEvent.ACTION_MOVE: {
                if (activePointerId < 0) return false;
                int p = e.findPointerIndex(activePointerId);
                if (p < 0) { activePointerId = -1; return false; }
                float x = e.getX(p), y = e.getY(p);
                //dbg("ACTION_MOVE");
                if (fingerTapPending) {
                    float slop = android.view.ViewConfiguration.get(GameActivity.this).getScaledTouchSlop();
                    if (Math.hypot(x - fingerDownX, y - fingerDownY) <= slop) {
                        return true;
                    }
                    // Moved far enough to be a drag: press where the finger landed, then follow it
                    fingerTapPending = false;
                    InputNativeInterface.sendCursorPos(fingerDownX * renderScale, fingerDownY * renderScale);
                    leftPressedFinger = true;
                    leftMouseDown = true;
                    InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
                }
                InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
                if (isMouseEvent(e, p)) {
                    syncMouseReleaseFromMask(e.getButtonState());
                }

                return true;
              }
              case MotionEvent.ACTION_UP:
              case MotionEvent.ACTION_POINTER_UP: {
                if (activePointerId < 0) return false;
                float x = e.getX(idx), y = e.getY(idx);
                if (fingerTapPending) {
                    fingerTapPending = false;
                    InputNativeInterface.sendCursorPos(fingerDownX * renderScale, fingerDownY * renderScale);
                    InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, true);
                    InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                    TouchpadControlElement.restoreMouseCursor();
                    activePointerId = -1;
                    return true;
                }
                if (leftPressedFinger) {
                  InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                  leftPressedFinger = false;
                }
                leftMouseDown = false;
                InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
                if (isMouseEvent(e, idx)) {
                    syncMouseReleaseFromMask(e.getButtonState());
                }
                if (!isMouseEvent(e, idx)) {
                    TouchpadControlElement.restoreMouseCursor();
                }
                activePointerId = -1;
                return true;
              }
              case MotionEvent.ACTION_CANCEL: {
                fingerTapPending = false;
                if (leftPressedFinger || leftMouseDown) {
                    InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
                    leftPressedFinger = false;
                    leftMouseDown = false;
                }
                activePointerId = -1;
                return true;
            }
          }
          return false;
        }
      });
    }

    // The game shows nothing until it has finished loading; on its first start (and after an update)
    // it also builds its texture atlases and other files from the mods, which on a slow phone takes
    // minutes. A still line of text there read as a hang, so the wait shows the app's logo, a bar that
    // keeps moving, the time so far and, when the game is preparing its files, why it takes long.
    // It all goes away once the game has presented a few frames of its own.
    private static final long FRAMES_BEFORE_GAME_IS_SHOWN = 5;
    // Without a first start to recognise (an update rebuilds only some files), explain any wait this long
    private static final long LOADING_HINT_AFTER_MS = 15_000;
    private android.view.View loadingView;
    private android.widget.TextView loadingText;
    private android.widget.TextView loadingHint;
    private long loadingStartedAt;

    private void setUpLoadingMessage() {
        android.widget.FrameLayout root = (android.widget.FrameLayout) binding.getRoot();
        final float dp = getResources().getDisplayMetrics().density;

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        box.addView(logo, new android.widget.LinearLayout.LayoutParams((int) (96 * dp), (int) (96 * dp)));

        com.google.android.material.progressindicator.LinearProgressIndicator bar =
                new com.google.android.material.progressindicator.LinearProgressIndicator(this);
        bar.setIndeterminate(true);
        android.widget.LinearLayout.LayoutParams barParams =
                new android.widget.LinearLayout.LayoutParams((int) (240 * dp), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = (int) (24 * dp);
        box.addView(bar, barParams);

        loadingText = new android.widget.TextView(this);
        loadingText.setText(R.string.game_loading);
        loadingText.setTextColor(0xFFFFFFFF);
        loadingText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        loadingText.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        android.widget.LinearLayout.LayoutParams textParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = (int) (16 * dp);
        box.addView(loadingText, textParams);

        loadingHint = new android.widget.TextView(this);
        loadingHint.setText(R.string.game_loading_preparing_files);
        loadingHint.setTextColor(0xB3FFFFFF);
        loadingHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        loadingHint.setGravity(android.view.Gravity.CENTER);
        loadingHint.setMaxWidth((int) (360 * dp));
        loadingHint.setVisibility(isGameFilesFirstBuild() ? android.view.View.VISIBLE : android.view.View.GONE);
        android.widget.LinearLayout.LayoutParams hintParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = (int) (8 * dp);
        box.addView(loadingHint, hintParams);

        // A new release is out: say so while the player waits, with the release page a tap away
        String newerVersion = UpdateChecker.newerVersion(this);
        String releaseUrl = UpdateChecker.releaseUrl(this);
        if (newerVersion != null && releaseUrl != null) {
            android.widget.TextView updateLine = new android.widget.TextView(this);
            updateLine.setText(getString(R.string.game_loading_update_available, newerVersion));
            updateLine.setTextColor(0xFFFFC107);
            updateLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            updateLine.setShadowLayer(4f, 0f, 0f, 0xFF000000);
            updateLine.setGravity(android.view.Gravity.CENTER);
            updateLine.setPaintFlags(updateLine.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            updateLine.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(releaseUrl)));
                } catch (android.content.ActivityNotFoundException ignored) {
                    // No browser: nothing to open it with
                }
            });
            android.widget.LinearLayout.LayoutParams updateParams = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            updateParams.topMargin = (int) (16 * dp);
            box.addView(updateLine, updateParams);
        }

        loadingView = box;
        root.addView(box, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        loadingStartedAt = android.os.SystemClock.elapsedRealtime();
        hudHandler.postDelayed(loadingTick, 250);
    }

    /** The game has never built its files from the mods: it writes assets/metadata/checksums.json when it has. */
    private boolean isGameFilesFirstBuild() {
        try {
            GameInstance instance = GameInstanceManager.requireSingleton().getInstanceByName(
                    getIntent().getStringExtra(EXTRA_GAME_INSTANCE_NAME));
            return instance != null && !new java.io.File(instance.getGamePath(), "assets/metadata/checksums.json").isFile();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private final Runnable loadingTick = new Runnable() {
        @Override public void run() {
            if (loadingView == null) return;
            if (GameLauncher.getPresentedFrameCount() >= FRAMES_BEFORE_GAME_IS_SHOWN) {
                ((android.view.ViewGroup) loadingView.getParent()).removeView(loadingView);
                loadingView = null;
                loadingText = null;
                loadingHint = null;
                return;
            }
            long elapsed = android.os.SystemClock.elapsedRealtime() - loadingStartedAt;
            long seconds = elapsed / 1000;
            loadingText.setText(getString(R.string.game_loading) + "  " + (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60));
            if (elapsed >= LOADING_HINT_AFTER_MS) {
                loadingHint.setVisibility(android.view.View.VISIBLE);
            }
            hudHandler.postDelayed(this, 250);
        }
    };

    /** Adds the chosen in-game overlay on top of the game surface and the on-screen controls. */
    private void setUpHud(int mode) {
        android.widget.FrameLayout root = (android.widget.FrameLayout) binding.getRoot();
        final float dp = getResources().getDisplayMetrics().density;
        if (mode == com.zomdroid.game.InstanceSettings.HUD_FPS) {
            fpsText = new android.widget.TextView(this);
            fpsText.setText("--");
            // Soft white in the top right corner: Deephall keeps its buttons along the top left
            fpsText.setTextColor(0xB3FFFFFF);
            fpsText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            fpsText.setShadowLayer(4f, 0f, 0f, 0xFF000000);   // outline so it reads on light scenes
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.END);
            int m = Math.round(8 * dp);
            lp.setMargins(0, m, m, 0);
            root.addView(fpsText, lp);
        } else if (mode == com.zomdroid.game.InstanceSettings.HUD_FULL) {
            // First segment names the graphics path: VK = ZINK (OpenGL on Vulkan), GL = the GLES
            // translators (GL4ES, NG_GL4ES, MobileGlues).
            LauncherPreferences.Renderer renderer = instanceSettings.getRenderer();
            String api = renderer == LauncherPreferences.Renderer.ZINK_OSMESA
                    || renderer == LauncherPreferences.Renderer.ZINK_ZFA ? "VK" : "GL";
            perfView = new PerfOverlayView(this, api);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            lp.setMargins(0, Math.round(4 * dp), 0, 0);
            root.addView(perfView, lp);   // not clickable: touches reach the controls underneath
            perfSampler = new PerfSampler(this);
            perfThread = new android.os.HandlerThread("PerfOverlay");
            perfThread.start();
            perfHandler = new android.os.Handler(perfThread.getLooper());
        }
    }

    private final Runnable fpsTextTick = new Runnable() {
        @Override public void run() {
            if (fpsText == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            long count = GameLauncher.getPresentedFrameCount();
            if (fpsLastTimeMs != 0 && now > fpsLastTimeMs)
                fpsText.setText(String.valueOf(Math.round((count - fpsLastCount) * 1000.0 / (now - fpsLastTimeMs))));
            fpsLastCount = count;
            fpsLastTimeMs = now;
            hudHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable perfTick = new Runnable() {
        @Override public void run() {
            if (perfView == null || perfSampler == null || perfHandler == null) return;
            final PerfSampler.Stats stats = perfSampler.sample(GameLauncher.getPresentedFrameCount());
            hudHandler.post(() -> { if (perfView != null) perfView.setStats(stats); });
            perfHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
      super.onDestroy();
      if (perfThread != null) { perfThread.quitSafely(); perfThread = null; perfHandler = null; }
      // Unregister GamepadManager to avoid leaks
      if (gamepadManager != null) {
          gamepadManager.unregister();
      }

      // Unregister Keyboard to avoid leaks
      if (keyboardManager != null) {
          keyboardManager.unregister();
      }
    }

    // GamepadManager.GamepadListener implementation

    // Called when any physical gamepad is connected: hide the virtual controller UI
    @Override
    public void onGamepadConnected() {
        runOnUiThread(() -> {
            isGamepadConnected = true;
            applyInputOverlay();
        });
    }

    // Called when all physical gamepads are disconnected: show the virtual controller UI
    @Override
    public void onGamepadDisconnected() {
        runOnUiThread(() -> {
            isGamepadConnected = false;
            applyInputOverlay();
        });
    }

    // Forward every gamepad button event to the native input interface
    @Override
    public void onGamepadButton(int button, boolean pressed) {
        InputNativeInterface.sendJoystickButton(button, pressed);
    }

    // Forward every gamepad axis event to the native input interface
    @Override
    public void onGamepadAxis(int axis, float value) {
        InputNativeInterface.sendJoystickAxis(axis, value);
    }

    // Forward every gamepad dpad event to the native input interface
    @Override
    public void onGamepadDpad(int dpad, char state) {
        InputNativeInterface.sendJoystickDpad(dpad, state);
    }

    // Handle gamepad key events
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean handled = false;
        //if (isKeyboardConnected && (keyboardManager != null)) handled |= keyboardManager.handleKeyEvent(event);
        if (keyboardManager != null) handled |= keyboardManager.handleKeyEvent(event);
        if (isGamepadConnected && (gamepadManager  != null)) handled |= gamepadManager.handleKeyEvent(event);
        if (handled) return true;
        //if (isKeyboardConnected) return true; // if physical kb connected not sending to typing
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean handled = false;
        //if (isKeyboardConnected && (keyboardManager != null)) handled |= keyboardManager.handleKeyEvent(event);
        if (keyboardManager != null) handled |= keyboardManager.handleKeyEvent(event);
        if (isGamepadConnected && (gamepadManager  != null)) handled |= gamepadManager.handleKeyEvent(event);
        if (handled) return true;
        //if (isKeyboardConnected) return true;
        return super.onKeyUp(keyCode, event);
    }


    // Handle gamepad/keyboard motion events
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
      //float renderScale = instanceSettings.getRenderScale();

      boolean isPointerDevice = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;

      if (!isPointerDevice) {
          if (gamepadManager != null && gamepadManager.handleMotionEvent(event)) return true;
          return super.onGenericMotionEvent(event);
      }

      int action = event.getActionMasked();
      int btn = event.getActionButton();

      // Cursor movement: always update position and if LMB/RMB is held — it's a drag of crosshair/objects
      //if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) {
      if (action == MotionEvent.ACTION_HOVER_MOVE) {
        float x = event.getX();
        float y = event.getY();
        InputNativeInterface.sendCursorPos(x * renderScale, y * renderScale);
        syncMouseReleaseFromMask(event.getButtonState());

        if (leftMouseDown || rightMouseDown) {
          //dbg("DRAG move");
          return true;
        }
        return true;
      }

      if (action == MotionEvent.ACTION_SCROLL) {
        float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (v == 0) {
            v = event.getAxisValue(MotionEvent.AXIS_WHEEL);
        }

        if (v != 0) {
            InputNativeInterface.sendMouseScroll(0.0, v > 0 ? 1.0 : -1.0);
        }
        return true;
      }

      if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
          boolean pressed = (action == MotionEvent.ACTION_BUTTON_PRESS);
          InputNativeInterface.sendCursorPos(event.getX() * renderScale, event.getY() * renderScale);

        if (btn == MotionEvent.BUTTON_PRIMARY) {
          //dbg(pressed ? "LMB PRESS" : "LMB RELEASE");
          leftMouseDown = pressed;
          InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, pressed);
          syncMouseReleaseFromMask(event.getButtonState());
          return true;
        } else if (btn == MotionEvent.BUTTON_SECONDARY) {
          //dbg(pressed ? "RMB PRESS" : "RMB RELEASE");
          rightMouseDown = pressed;
          InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_RIGHT.code, pressed);
          syncMouseReleaseFromMask(event.getButtonState());
          return true;
        } else if (btn == MotionEvent.BUTTON_TERTIARY) {
          // The wheel click: Deephall pans the map with it, and its debug menu acts on it
          middleMouseDown = pressed;
          InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_WHEEL.code, pressed);
          syncMouseReleaseFromMask(event.getButtonState());
          return true;
        }
      }
      return super.onGenericMotionEvent(event);
    }

    @Override
    public void onKeyboardConnected() {
        runOnUiThread(() -> {
            if (binding == null) return;
            isKeyboardConnected = true;
            // 1) Жёстко выключаем IME-режим SurfaceView
            systemKeyboardVisible = false;
            if (binding.gameSv != null) {
                binding.gameSv.setAcceptingTextInput(false);
            }
            hideSystemKeyboard(); // на всякий случай
            binding.inputControlsV.setKeyboardConnected(true);
            reapplyImmersiveMode();
            applyInputOverlay();
        });
    }

    @Override
    public void onKeyboardDisconnected() {
        runOnUiThread(() -> {
            if (binding == null) return;
            isKeyboardConnected = false;
            binding.inputControlsV.setKeyboardConnected(false);
            applyInputOverlay();
        });
    }

    @Override
    public void onKeyboardKey(int glfwCode, boolean pressed) {
      InputNativeInterface.sendKeyboard(glfwCode, pressed);
    }

    private void applyInputOverlay() {
      if (binding.inputControlsV == null) return;
      binding.inputControlsV.setGamepadConnected(isGamepadConnected);

      if (isKeyboardConnected) {
        binding.inputControlsV.setVisibility(View.GONE);
      } else if (isGamepadConnected) {
        binding.inputControlsV.setVisibility(View.VISIBLE);
        binding.inputControlsV.applyInputMode(InputControlsView.InputMode.MNK);
      } else {
        binding.inputControlsV.setVisibility(View.VISIBLE);
        binding.inputControlsV.applyInputMode(InputControlsView.InputMode.ALL);
      }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gamepadManager != null)  gamepadManager.register();
        if (keyboardManager != null) keyboardManager.register();
        if (fpsText != null) {
            fpsLastTimeMs = 0;                          // the first interval after a pause is not a rate
            hudHandler.removeCallbacks(fpsTextTick);
            hudHandler.postDelayed(fpsTextTick, 1000);
        }
        if (perfHandler != null) {
            perfHandler.removeCallbacks(perfTick);
            perfHandler.post(perfTick);                 // the first sample only primes the deltas
        }
    }

    @Override
    protected void onPause() {
        if (gamepadManager != null)  gamepadManager.unregister();
        if (keyboardManager != null) keyboardManager.unregister();
        hudHandler.removeCallbacks(fpsTextTick);
        if (perfHandler != null) perfHandler.removeCallbacks(perfTick);
        super.onPause();
    }

    /**
     * Going to the background: ask Deephall to save, since Android may kill the app while it is away. The game
     * keeps running in the background (it renders into a 1x1 pbuffer once the surface is gone), checks for this
     * file every 2 seconds, deletes it and saves (PhoneAutosaver in the game).
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isGameStarted) return;
        try {
            GameInstance instance = GameInstanceManager.requireSingleton().getInstanceByName(
                    getIntent().getStringExtra(EXTRA_GAME_INSTANCE_NAME));
            if (instance != null && BundledGame.PRESET_NAME.equals(instance.getPresetName())) {
                java.io.File request = new java.io.File(instance.getGamePath(), "save-request");
                if (!request.exists() && !request.createNewFile()) {
                    Log.w(LOG_TAG, "Could not create " + request);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            Log.w(LOG_TAG, "Could not ask the game to save", e);
        }
    }

    private boolean isMouseEvent(MotionEvent e, int pointerIndex) {
        return e.isFromSource(InputDevice.SOURCE_MOUSE)
            || e.isFromSource(InputDevice.SOURCE_TOUCHPAD)
            || (pointerIndex >= 0 && pointerIndex < e.getPointerCount()
                && e.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_MOUSE);
    }

    private void syncMouseReleaseFromMask(int mask) {
        boolean leftNow  = (mask & MotionEvent.BUTTON_PRIMARY)   != 0;
        boolean rightNow = (mask & MotionEvent.BUTTON_SECONDARY) != 0;

        if (!leftNow && leftMouseDown) {
            leftMouseDown = false;
            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_LEFT.code, false);
        }
        if (!rightNow && rightMouseDown) {
            rightMouseDown = false;
            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_RIGHT.code, false);
        }
        boolean middleNow = (mask & MotionEvent.BUTTON_TERTIARY) != 0;
        if (!middleNow && middleMouseDown) {
            middleMouseDown = false;
            InputNativeInterface.sendMouseButton(GLFWBinding.MOUSE_BUTTON_WHEEL.code, false);
        }
    }

    public void showSystemKeyboard() {
        binding.gameSv.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(binding.gameSv, InputMethodManager.SHOW_FORCED);
        }
    }

    public void hideSystemKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.gameSv.getWindowToken(), 0);
        }
    }

    private void toggleSystemKeyboard() {
        if (isKeyboardConnected) return; // физическая клавиатура — не трогаем
        boolean next = !systemKeyboardVisible;
        binding.gameSv.setAcceptingTextInput(next);
        systemKeyboardVisible = next;
    }

    private void reapplyImmersiveMode() {
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars()
                    | WindowInsets.Type.navigationBars()
                    | WindowInsets.Type.ime());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        binding.gameSv.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK
                || kc == KeyEvent.KEYCODE_VOLUME_UP
                || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }

        boolean physicalKeyboardEvent = isTruePhysicalKeyboardEvent(event);
        boolean textInputMode = binding != null
                && binding.gameSv != null
                && binding.gameSv.isAcceptingTextInput();

        // Блокируем "утечку" физических клавиш в IME только когда НЕ идёт осознанный text input.
        if (isKeyboardConnected && physicalKeyboardEvent && !textInputMode) {
            if (keyboardManager != null && keyboardManager.handleKeyEvent(event)) {
                return true;
            }
            return true; // глушим, чтобы Gboard не превращал аппаратный ввод в typing
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean isTruePhysicalKeyboardEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null) return false;

        // Геймпад тоже репортит SOURCE_KEYBOARD для кнопок A/B/X/Y/Start —
        // исключаем его явно, иначе геймпадные кнопки тоже будут заглушены
        boolean isGamepad = (device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (isGamepad) return false;

        return !device.isVirtual()
                && (event.isFromSource(InputDevice.SOURCE_KEYBOARD)
                || (device.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD);
    }
}
