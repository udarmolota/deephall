package com.zomdroid.input;

import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.function.Supplier;

/**
 * Fingers as a mouse, for a game that expects one - Deephall rather than Project Zomboid, which
 * keeps its own touch model in GameActivity.
 *
 * <pre>
 *   tap                           left click
 *   drag                          left drag (box selection)
 *   hold still                    right click, with a short buzz so the player knows it fired
 *   two fingers apart or together mouse wheel (zoom)
 *   two fingers moving as one     middle-button drag, which is how the game pans its camera
 * </pre>
 *
 * The left button is not pressed when the finger lands, only once the gesture is known: a tap
 * sends press and release together when the finger lifts, a drag presses as soon as the finger
 * leaves its slop. That is what lets a held finger become a right click without the game having
 * seen a left click first.
 *
 * Two fingers are decided once, the same way: whichever passes its slop first - the distance
 * between the fingers or the point between them - makes the gesture a zoom or a pan until every
 * finger is up. The game ignores the wheel while the middle button is held, so the two cannot be
 * mixed anyway.
 */
public class TouchMouseGestures {

    private static final int LEFT = GLFWBinding.MOUSE_BUTTON_LEFT.code;
    private static final int RIGHT = GLFWBinding.MOUSE_BUTTON_RIGHT.code;
    private static final int MIDDLE = GLFWBinding.MOUSE_BUTTON_WHEEL.code;

    // One wheel notch per 10% of spread, as the Zomboid pinch in GameActivity does.
    private static final float ZOOM_NOTCH = 0.10f;

    private enum OneFinger { NONE, PENDING, LEFT_DRAG, RIGHT_DONE }
    private enum TwoFingers { NONE, UNDECIDED, ZOOM, PAN, DONE }

    private final View view;
    private final Supplier<Float> renderScale;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float touchSlop;
    private final float twoFingerSlop;
    private final long longPressTimeout;

    private OneFinger oneFinger = OneFinger.NONE;
    private int activePointerId = -1;
    private float downX, downY;

    private TwoFingers twoFingers = TwoFingers.NONE;
    private float startSpan, lastSpan, startMidX, startMidY;
    private float zoomAccumulated;

    private final Runnable longPress = () -> {
        if (oneFinger != OneFinger.PENDING) return;
        oneFinger = OneFinger.RIGHT_DONE;
        TouchMouseGestures.this.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        InputNativeInterface.sendMouseButton(RIGHT, true);
        InputNativeInterface.sendMouseButton(RIGHT, false);
    };

    public TouchMouseGestures(View view, Supplier<Float> renderScale) {
        this.view = view;
        this.renderScale = renderScale;
        ViewConfiguration configuration = ViewConfiguration.get(view.getContext());
        this.touchSlop = configuration.getScaledTouchSlop();
        this.twoFingerSlop = configuration.getScaledTouchSlop() * 2f;
        this.longPressTimeout = ViewConfiguration.getLongPressTimeout();
    }

    public boolean onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onFirstFingerDown(e);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A third finger changes nothing.
                if (e.getPointerCount() == 2) onSecondFingerDown(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (twoFingers != TwoFingers.NONE) onTwoFingersMove(e);
                else onOneFingerMove(e);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // Lifting one of two fingers ends the two-finger gesture; the finger left behind
                // does nothing until it is lifted too.
                if (twoFingers != TwoFingers.NONE) endTwoFingers();
                return true;
            case MotionEvent.ACTION_UP:
                if (twoFingers != TwoFingers.NONE) endTwoFingers();
                else onOneFingerUp(e);
                reset();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                return true;
        }
        return false;
    }

    /** Lets go of anything still held, for when the touch stream is taken away from us. */
    public void cancel() {
        if (oneFinger == OneFinger.LEFT_DRAG) InputNativeInterface.sendMouseButton(LEFT, false);
        if (twoFingers == TwoFingers.PAN) InputNativeInterface.sendMouseButton(MIDDLE, false);
        reset();
    }

    private void reset() {
        handler.removeCallbacks(longPress);
        oneFinger = OneFinger.NONE;
        twoFingers = TwoFingers.NONE;
        activePointerId = -1;
    }

    // ---- one finger ----

    private void onFirstFingerDown(MotionEvent e) {
        reset();
        activePointerId = e.getPointerId(0);
        downX = e.getX(0);
        downY = e.getY(0);
        moveCursor(downX, downY);
        oneFinger = OneFinger.PENDING;
        handler.postDelayed(longPress, longPressTimeout);
    }

    private void onOneFingerMove(MotionEvent e) {
        int p = e.findPointerIndex(activePointerId);
        if (p < 0) return;
        float x = e.getX(p), y = e.getY(p);
        switch (oneFinger) {
            case PENDING:
                if (Math.hypot(x - downX, y - downY) > touchSlop) {
                    handler.removeCallbacks(longPress);
                    // The cursor is still where the finger landed, so the drag starts there.
                    InputNativeInterface.sendMouseButton(LEFT, true);
                    oneFinger = OneFinger.LEFT_DRAG;
                    moveCursor(x, y);
                }
                break;
            case LEFT_DRAG:
                moveCursor(x, y);
                break;
            default:
                // A right click has been made; the finger is only waiting to be lifted.
                break;
        }
    }

    private void onOneFingerUp(MotionEvent e) {
        handler.removeCallbacks(longPress);
        if (oneFinger == OneFinger.PENDING) {
            InputNativeInterface.sendMouseButton(LEFT, true);
            InputNativeInterface.sendMouseButton(LEFT, false);
        } else if (oneFinger == OneFinger.LEFT_DRAG) {
            int p = e.getActionIndex();
            moveCursor(e.getX(p), e.getY(p));
            InputNativeInterface.sendMouseButton(LEFT, false);
        }
    }

    // ---- two fingers ----

    private void onSecondFingerDown(MotionEvent e) {
        handler.removeCallbacks(longPress);
        if (oneFinger == OneFinger.LEFT_DRAG) {
            // A drag that picks up a second finger stops being a drag.
            InputNativeInterface.sendMouseButton(LEFT, false);
        }
        oneFinger = OneFinger.NONE;
        twoFingers = TwoFingers.UNDECIDED;
        startSpan = span(e);
        startMidX = midX(e);
        startMidY = midY(e);
    }

    private void onTwoFingersMove(MotionEvent e) {
        if (e.getPointerCount() < 2) return;
        float span = span(e), midX = midX(e), midY = midY(e);
        switch (twoFingers) {
            case UNDECIDED:
                if (Math.abs(span - startSpan) > twoFingerSlop) {
                    twoFingers = TwoFingers.ZOOM;
                    // Count from here: the slop itself is not a zoom.
                    lastSpan = span;
                    zoomAccumulated = 0f;
                    moveCursor(midX, midY);
                } else if (Math.hypot(midX - startMidX, midY - startMidY) > twoFingerSlop) {
                    twoFingers = TwoFingers.PAN;
                    moveCursor(startMidX, startMidY);
                    InputNativeInterface.sendMouseButton(MIDDLE, true);
                    moveCursor(midX, midY);
                }
                break;
            case ZOOM:
                if (lastSpan > 0f) zoomAccumulated += span / lastSpan - 1f;
                lastSpan = span;
                while (zoomAccumulated > ZOOM_NOTCH) {
                    zoomAccumulated -= ZOOM_NOTCH;
                    InputNativeInterface.sendMouseScroll(0.0, 1.0);
                }
                while (zoomAccumulated < -ZOOM_NOTCH) {
                    zoomAccumulated += ZOOM_NOTCH;
                    InputNativeInterface.sendMouseScroll(0.0, -1.0);
                }
                break;
            case PAN:
                moveCursor(midX, midY);
                break;
            default:
                break;
        }
    }

    private void endTwoFingers() {
        if (twoFingers == TwoFingers.PAN) InputNativeInterface.sendMouseButton(MIDDLE, false);
        // Stay silent until every finger is up.
        twoFingers = TwoFingers.DONE;
    }

    // ---- helpers ----

    private void moveCursor(float x, float y) {
        float scale = renderScale.get();
        InputNativeInterface.sendCursorPos(x * scale, y * scale);
    }

    private static float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static float midX(MotionEvent e) {
        return (e.getX(0) + e.getX(1)) / 2f;
    }

    private static float midY(MotionEvent e) {
        return (e.getY(0) + e.getY(1)) / 2f;
    }
}
