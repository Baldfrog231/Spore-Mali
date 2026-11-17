package com.sporere.game;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Touch controls for Spore, drawn over SDL's surface. The game is played with the mouse; touches become SDL mouse
 * input in one of two modes (settings):
 *   Direct:   the finger is the cursor. Tap = click, drag = left drag, long press = right click.
 *   Cursor:   a cursor on screen; dragging moves it, tap = click where it is, double tap and hold = left drag,
 *             long press = right click, a joystick in the corner nudges it. Easier on Spore's small buttons.
 * In both: two fingers drag with the right button held (camera rotation), pinching turns the wheel (zoom).
 * A menu handle (draggable, so it can sit wherever the current stage leaves room) opens a row of buttons: Esc (the
 * game's menu, "back"), Cam (one-finger drags rotate the camera while on), Controls (the movement stick), Undo (Ctrl+Z, the
 * editors), the keyboard (names) and the gear: the settings panel. Engine settings (resolution, textures, frame limit, battery
 * saver) go to sporere.ini and apply on the next start; the rest apply at once.
 * Each stage gets its own layout (the game mode comes from libmain.so): creature: the WASD stick and ability keys 1-4;
 * tribe and civilization: RMB (taps give orders); space: tool keys 1-4 and RMB; editors: Undo and Redo.
 */
public class SporeControls extends View {
    private static final int LEFT = MotionEvent.BUTTON_PRIMARY, RIGHT = MotionEvent.BUTTON_SECONDARY,
            MIDDLE = MotionEvent.BUTTON_TERTIARY;

    /**
     * The button a two-finger drag holds to turn the camera: the middle one (the wheel) in the tribe and civilization
     * stages, where the right button gives orders and does not turn; the right one elsewhere.
     */
    private int turnButton() {
        return stage == Stage.TRIBE || stage == Stage.CIV || stage == Stage.SPACE ? MIDDLE : RIGHT;
    }
    private static final int DOWN = MotionEvent.ACTION_DOWN, UP = MotionEvent.ACTION_UP, MOVE = MotionEvent.ACTION_MOVE;
    private static final long LONG_PRESS_MS = 500, LEFT_DELAY_MS = 60, DOUBLE_TAP_MS = 280;
    private static final int TEAL = 0xff40d6c8;
    private static final int AUTO = -1;  // Screen and Safe zone: follow the window and the system's safe area

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    // ---- settings ----
    /** A row of the settings panel; its arrows (or a tap on the row) step through the values. */
    private static final class Setting {
        final String en, ruLabel, key;
        final String[] names;
        final int[] values;
        final boolean engine;  // written to sporere.ini, applies on the next start
        final boolean ini;     // kept in sporere.ini but applied at once (FPS limit)
        String[] codes;        // written to sporere.ini as these strings instead of the numbers (language)
        int index;

        Setting(String en, String ruLabel, String key, String[] names, int[] values, int def, boolean engine,
                boolean ini) {
            this.en = en;
            this.ruLabel = ruLabel;
            this.key = key;
            this.names = names;
            this.values = values;
            this.engine = engine;
            this.ini = ini;
            index = 0;
            for (int i = 0; i < values.length; ++i) if (values[i] == def) index = i;
        }

        int value() {
            return values[index];
        }

        String label() {
            return ru() ? ruLabel : en;
        }
    }

    private static String[] tr(String[] en, String[] ruNames) {
        return ru() ? ruNames : en;
    }

    // The game's language (Data/Locale folders of the GOG release), each in its own language; Auto follows the phone.
    private static final String[] LANG_CODES = {"auto", "en-us", "en-gb", "ru-ru", "de-de", "fr-fr", "es-es", "it-it",
            "pt-br", "pt-pt", "pl-pl", "nl-nl", "cs-cz", "hu-hu", "sv-se", "da-dk", "no-no", "fi-fi", "el-gr", "ja-jp",
            "ko-kr", "zh-cn", "zh-tw", "th-th"};
    private static final String[] LANG_NAMES = {"Auto", "English (US)", "English (UK)", "\u0420\u0443\u0441\u0441\u043a\u0438\u0439",
            "Deutsch", "Fran\u00e7ais", "Espa\u00f1ol", "Italiano", "Portugu\u00eas (BR)", "Portugu\u00eas (PT)", "Polski",
            "Nederlands", "\u010ce\u0161tina", "Magyar", "Svenska", "Dansk", "Norsk", "Suomi",
            "\u0395\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ac", "\u65e5\u672c\u8a9e", "\ud55c\uad6d\uc5b4",
            "\u7b80\u4f53\u4e2d\u6587", "\u7e41\u9ad4\u4e2d\u6587", "\u0e44\u0e17\u0e22"};

    private static int[] indices(int n) {
        int[] v = new int[n];
        for (int i = 0; i < n; ++i) v[i] = i;
        return v;
    }

    private static final String[] OFF_ON = tr(new String[] {"Off", "On"}, new String[] {"Выкл", "Вкл"});
    // Engine settings (*) go to sporere.ini and apply on the next start; the others at once.
    private final Setting sQuality = new Setting("Quality", "Качество", "gpu_tier",
            tr(new String[] {"Fast", "Balanced", "Pretty"}, new String[] {"Быстро", "Баланс", "Красиво"}),
            new int[] {0, 1, 2}, 0, true, false);
    // The game draws its interface in pixels of its resolution: a higher one is sharper, with a smaller interface.
    private final Setting sResolution = new Setting("Resolution", "Разрешение", "render_height",
            new String[] {"540p", "600p", "720p", "900p", "1080p"}, new int[] {540, 600, 720, 900, 1080}, 600, true,
            false);
    private final Setting sFps = new Setting("FPS limit", "Лимит FPS", "fps_limit",
            tr(new String[] {"Off", "30", "40", "45", "50", "60", "90", "120"},
               new String[] {"Выкл", "30", "40", "45", "50", "60", "90", "120"}),
            new int[] {0, 30, 40, 45, 50, 60, 90, 120}, 60, false, true);
    // The stretch from the render size to the screen: plain, or sharpened (DXVK present shader, dxvk-sharpen.patch).
    private final Setting sSharpen = new Setting("Sharpness", "Резкость", "sharpen",
            tr(new String[] {"Off", "Low", "Medium", "High", "Max"},
               new String[] {"Выкл", "Слабая", "Средняя", "Сильная", "Макс"}),
            new int[] {0, 3, 5, 7, 10}, 5, true, false);
    // Off: the CPU prepares the next frame while the GPU draws (smoother on most phones); On: the game's own wait.
    private final Setting sGpuSync = new Setting("Wait for GPU", "Ждать GPU", "gpu_sync", OFF_ON, new int[] {0, 1}, 0,
            true, false);
    // Auto: the picture fills the window at its own aspect ratio (the system's safe area only, no letterbox);
    // Full: the whole screen; the ratios letterbox the picture; Safe area keeps it clear of the cutout and corners.
    // The surface is sized before the game's first frame, so a change applies on the next start.
    private final Setting sScreen = new Setting("Screen", "Экран", "screen_fit",
            tr(new String[] {"Auto", "Full", "16:9", "4:3", "16:10", "21:9", "Safe area"},
               new String[] {"Авто", "Весь", "16:9", "4:3", "16:10", "21:9", "Без углов"}),
            new int[] {AUTO, 0, 1, 3, 4, 5, 2}, AUTO, true, false);
    // Extra margin inside every screen edge. Auto follows the system's own safe area (cutout, rounded corners);
    // the dp values add a fixed margin. Tapping the row opens the frame editor instead of stepping values.
    private final Setting sSafe = new Setting("Safe zone", "Безопасные края", "safe_zone",
            new String[] {"Auto", "0", "8", "16", "24", "32", "40"},
            new int[] {AUTO, 0, 8, 16, 24, 32, 40}, AUTO, false, false);
    private final Setting sShowFps = new Setting("Show FPS", "Показывать FPS", "ui_fps", OFF_ON, new int[] {0, 1}, 0,
            false, false);
    // Cursor: the pointer (a virtual pointer moved by dragging or the corner joystick) is used in the editors only,
    // where dragging parts needs precision; in the stages a touch acts where it lands. Direct: no cursor at all.
    private final Setting sMode = new Setting("Touch mode", "Касания", "ui_touchpad",
            tr(new String[] {"Direct", "Cursor"}, new String[] {"Прямые", "Курсор"}), new int[] {0, 1}, 0, false,
            false);
    private final Setting sSpeed = new Setting("Camera speed", "Скорость камеры", "ui_speed",
            tr(new String[] {"Slow", "Normal", "Fast"}, new String[] {"Медленно", "Обычно", "Быстро"}),
            new int[] {60, 100, 160}, 100, false, false);
    // How far the touchpad cursor moves per finger drag, in percent of the drag (the camera keeps its own speed).
    private final Setting sCursor = new Setting("Cursor speed", "Скорость курсора", "ui_cursor",
            new String[] {"50%", "75%", "100%", "150%", "200%", "300%"}, new int[] {50, 75, 100, 150, 200, 300}, 100,
            false, false);
    private final Setting sScale = new Setting("Button size", "Размер кнопок", "ui_scale",
            new String[] {"80%", "100%", "120%", "140%"}, new int[] {80, 100, 120, 140}, 100, false, false);
    private final Setting sAlpha = new Setting("Button opacity", "Видимость кнопок", "ui_alpha",
            new String[] {"40%", "60%", "80%", "100%"}, new int[] {40, 60, 80, 100}, 80, false, false);
    private final Setting sLanguage = new Setting("Language", "\u042f\u0437\u044b\u043a", "locale", LANG_NAMES,
            indices(LANG_CODES.length), 0, true, false);

    {
        sLanguage.codes = LANG_CODES;
    }

    private final Setting sVibrate = new Setting("Vibration", "Вибрация", "ui_vibrate", OFF_ON, new int[] {0, 1}, 1,
            false, false);
    // The panel's main page shows the graphics settings in two columns; the controls are on their own page behind
    // "More", so the panel stays short enough for the whole footer to fit on a phone's screen.
    private final Setting[] graphicsLeft = {sQuality, sResolution, sSharpen, sFps};
    private final Setting[] graphicsRight = {sGpuSync, sScreen, sShowFps};
    private final Setting[] controlsSettings = {sLanguage, sVibrate, sSafe, sMode, sCursor, sSpeed, sScale, sAlpha};
    private final Setting[] settings = {sQuality, sResolution, sSharpen, sFps, sGpuSync, sScreen, sShowFps, sLanguage, sMode,
            sCursor, sSpeed, sScale, sAlpha, sVibrate, sSafe};
    private final RectF[] settingRects = new RectF[settings.length];
    private boolean engineChanged;

    /** Sets the frame rate cap at once (libmain.so, d3d9.cpp). */
    static native void nativeSetFpsLimit(int fps);

    // ---- layout editor ----
    // Every on-screen control can be moved: the menu handle, the stick, the jump button and six slots for the
    // stage's action buttons (1-4, Order, Select, Undo...). Places are fractions of the screen in prefs "lay_*".
    private static final String[] SLOT_IDS = {"slot0", "slot1", "slot2", "slot3", "slot4", "slot5"};
    private static final float[][] SLOT_DEFAULTS = {{0.94f, 0.30f}, {0.94f, 0.46f}, {0.94f, 0.62f}, {0.85f, 0.38f},
            {0.85f, 0.54f}, {0.85f, 0.70f}};
    private boolean editLayout;
    private String editing;   // the element being dragged
    private String selected;  // the element picked in the editor; its size bar is shown
    private boolean resizing; // the size bar is being dragged
    private final RectF editDone = new RectF(), editReset = new RectF(), editSizeBar = new RectF();
    private final RectF panelLayout = new RectF();
    private static final float SIZE_MIN = 0.5f, SIZE_MAX = 2f;

    private float layX(String id, float def) {
        return prefs.getFloat("lay_" + id + "_x", def);
    }

    private float layY(String id, float def) {
        return prefs.getFloat("lay_" + id + "_y", def);
    }

    /** The player's size factor for one control (the editor's size bar); 1 = the default size. */
    private float sizeOf(String id) {
        return prefs.getFloat("lay_" + id + "_size", 1f);
    }

    private float scale() {
        return sScale.value() / 100f;
    }

    private int alphaOf(int argb) {
        int a = (argb >>> 24) * sAlpha.value() / 100;
        return (a << 24) | (argb & 0xffffff);
    }

    // ---- side buttons ----
    private static final class Button {
        final String label;
        final Runnable action;
        final RectF rect = new RectF();

        Button(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private final List<Button> buttons = new ArrayList<>();
    private Button pressedButton;
    private boolean camLock;  // one-finger drags hold the right button (camera)
    private SporeStick stick;  // WASD movement stick (creature and tribal stages), shown with the WASD button
    // The menu handle: one round button the player can drag anywhere (Spore's own controls sit in different
    // corners in each stage); tapping it opens a row of buttons. Its place is kept as a fraction of the screen.
    private final RectF handle = new RectF();
    private float handleFx, handleFy;
    private boolean menuOpen, handleDragged;

    void setStick(SporeStick s) {
        stick = s;
    }

    // ---- per-stage layout ----
    // The active game mode (libmain.so reads it from the game once a frame) picks a set of action buttons along the
    // right edge, whether the WASD stick shows (remembered per stage once toggled) and whether taps give orders
    // (right clicks) in the strategy stages.
    private enum Stage { OTHER, CELL, CREATURE, TRIBE, CIV, SPACE, EDITOR }
    private Stage stage = Stage.OTHER;
    private final List<Button> actions = new ArrayList<>();
    private boolean rightTaps;  // Order button: one-finger taps are right clicks (orders to the selected units)
    private boolean boxArmed;   // Select button: the next one-finger drag is a left drag (box selection)
    private String hint;        // what the gestures do in this stage, shown for a few seconds after it starts
    private final Runnable hideHint = () -> {
        hint = null;
        invalidate();
    };

    /** In the creature and space stages a one-finger drag turns the camera (right drag). */
    private boolean dragTurnsCamera() {
        if (camLock) return true;
        if (boxArmed) return false;
        return stage == Stage.CREATURE;
    }

    /**
     * In the space stage the ship flies toward the cursor while the right button is held (the game's own hint:
     * "use the right mouse button to fly"): a one-finger drag holds it with the cursor under the finger.
     */
    private boolean dragFlies() {
        return !camLock && !boxArmed && stage == Stage.SPACE;
    }

    /**
     * In the tribe and civilization stages a one-finger drag moves the map, as in mobile strategy games: the game
     * pans with W/A/S/D, so the keys toward which the view should go are held while the finger is away from where it
     * went down (the map follows the finger).
     */
    private boolean dragPansMap() {
        return !camLock && !boxArmed && (stage == Stage.TRIBE || stage == Stage.CIV);
    }

    private static final int[] PAN_KEYS = {KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D};
    private final boolean[] panHeld = new boolean[4];

    private void panKeys(float dx, float dy) {
        float dead = 18 * density;
        // finger right -> the map moves right -> the view goes left (A); finger down -> the view goes up (W)
        boolean[] want = {dy > dead, dx > dead, dy < -dead, dx < -dead};
        for (int i = 0; i < 4; ++i) {
            if (want[i] == panHeld[i]) continue;
            panHeld[i] = want[i];
            if (want[i]) SDLActivity.onNativeKeyDown(PAN_KEYS[i]);
            else SDLActivity.onNativeKeyUp(PAN_KEYS[i]);
        }
    }

    static boolean ru() {
        return "ru".equals(Locale.getDefault().getLanguage()) || "uk".equals(Locale.getDefault().getLanguage());
    }

    private static String hintFor(Stage s) {
        boolean ru = ru();
        switch (s) {
            case CELL:
                return ru ? "Клетка: тап или веди пальцем — плыть, щипок — зум"
                          : "Cell: tap or drag to swim, pinch to zoom";
            case CREATURE:
                return ru ? "Существо: тап — идти / действовать, палец — камера, стик — ходьба, 1–4 — способности"
                          : "Creature: tap to walk / act, drag to turn the camera, stick to walk, 1-4 abilities";
            case TRIBE:
            case CIV:
                return ru ? "Тап — выбрать, удержание — приказ, палец — двигать карту, два пальца — поворот, «Выбор» — рамка"
                          : "Tap to select, hold to order, drag to move the map, two fingers turn, Select for a box";
            case SPACE:
                return ru ? "Космос: стик (W/S) — полёт, держи палец — лететь туда, тап — выбрать, два пальца — камера, +/− — высота"
                          : "Space: stick (W/S) flies, hold a finger to fly there, tap to pick, two fingers turn, +/- altitude";
            case EDITOR:
                return ru ? "Редактор: тяни детали пальцем, два пальца — камера, щипок — зум, коснись детали и \u25b2/\u25bc — размер"
                          : "Editor: drag parts, two fingers turn the camera, pinch to zoom, tap a part and \u25b2/\u25bc to resize it";
            default:
                return null;
        }
    }

    /** The active game mode id (App::cGameModeManager), 0 if none. */
    static native int nativeGameMode();

    private static Stage stageOf(int mode) {
        switch (mode) {
            case 0x1654c00: return Stage.CELL;
            case 0x1654c01: return Stage.CREATURE;
            case 0x1654c02: return Stage.TRIBE;
            case 0x1654c04: return Stage.CIV;
            case 0x1654c05: return Stage.SPACE;
            case 0x0dbdba1: return Stage.EDITOR;
            default: return Stage.OTHER;
        }
    }

    private final Runnable modeTick = new Runnable() {
        @Override
        public void run() {
            Stage s = Stage.OTHER;
            try {
                s = stageOf(nativeGameMode());
            } catch (UnsatisfiedLinkError ignored) {
                // the library is not loaded yet
            }
            if (s != stage) enterStage(s);
            handler.postDelayed(this, 500);
        }
    };

    private void enterStage(Stage s) {
        if (s != Stage.EDITOR) forceCursor = false;  // a new stage starts in direct touch again
        stage = s;
        rightTaps = false;
        boxArmed = false;
        actions.clear();
        hint = hintFor(s);
        handler.removeCallbacks(hideHint);
        if (hint != null) handler.postDelayed(hideHint, 6000);
        switch (s) {
            case CREATURE:  // abilities on 1-4 (attack or social, the game swaps the bar), WASD + jump on the stick
                for (int k = 1; k <= 4; ++k) actions.add(keyButton(String.valueOf(k), KeyEvent.KEYCODE_0 + k));
                break;
            case TRIBE:
            case CIV:
                actions.add(new Button(ru() ? "Приказ" : "Order", this::toggleRightTaps));
                actions.add(new Button(ru() ? "Выбор" : "Select", this::armBox));
                break;
            case SPACE:  // tools on the number keys, altitude on +/- (main keyboard and keypad: whichever the game reads)
                for (int k = 1; k <= 4; ++k) actions.add(keyButton(String.valueOf(k), KeyEvent.KEYCODE_0 + k));
                actions.add(keysButton("+", KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD));
                actions.add(keysButton("\u2212", KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT));
                break;
            case EDITOR:
                actions.add(new Button("Undo", this::undo));
                actions.add(new Button("Redo", this::redo));
                // the mouse wheel over a part resizes it: tap the part (the cursor stays there), then these
                actions.add(new Button("\u25b2", () -> wheel(1)));
                actions.add(new Button("\u25bc", () -> wheel(-1)));
                break;
            default:
                break;
        }
        if (stick != null) {
            // tribe and civilization: the map moves with a finger, the stick only when asked for (WASD button)
            boolean show = prefs.getBoolean("stick3_" + s.name(), s == Stage.CREATURE || s == Stage.SPACE);
            if (show) stick.setVisibility(View.VISIBLE);
            else if (stick.getVisibility() == View.VISIBLE) stick.hide();
        }
        invalidate();
    }

    private Button keysButton(String label, int... keycodes) {
        return new Button(label, () -> {
            for (int k : keycodes) SDLActivity.onNativeKeyDown(k);
            handler.postDelayed(() -> {
                for (int k : keycodes) SDLActivity.onNativeKeyUp(k);
            }, 250);
        });
    }

    private Button keyButton(String label, int keycode) {
        return new Button(label, () -> {
            SDLActivity.onNativeKeyDown(keycode);
            handler.postDelayed(() -> SDLActivity.onNativeKeyUp(keycode), 120);
        });
    }

    private void toggleRightTaps() {
        rightTaps = !rightTaps;
        invalidate();
    }

    private void armBox() {
        boxArmed = !boxArmed;
        invalidate();
    }

    private Button hitAction(float x, float y) {
        for (Button b : actions) if (b.rect.contains(x, y)) return b;
        return null;
    }

    // ---- gesture state ----
    private enum Mode { NONE, PENDING, LEFT_DRAG, CAM_DRAG, PAN, FLY, CURSOR, TWO_FINGER, BUTTON, PANEL, HANDLE, EDIT, JOY }
    private Mode mode = Mode.NONE;
    private float downX, downY, lastX, lastY, lastMidX, lastMidY, lastSpan, wheelAccum;
    private float camX, camY;            // virtual pointer while rotating the camera (moves at the camera speed)
    private float cursorX = -1, cursorY;  // touchpad cursor
    private boolean forceCursor;          // Cursor picked in a stage: the cursor comes back until the stage ends
    private float joyDX, joyDY;           // the Cursor-mode corner joystick's knob offset
    private final Runnable joyTick = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.JOY) return;
            float r = joyR() * 0.6f;
            float dx = joyDX / r, dy = joyDY / r;
            if (Math.abs(dx) < 0.12f) dx = 0;  // a parked thumb must not drift
            if (Math.abs(dy) < 0.12f) dy = 0;
            if (dx != 0 || dy != 0) {
                if (cursorX < 0) {
                    cursorX = getWidth() / 2f;
                    cursorY = getHeight() / 2f;
                }
                float step = 760 * density * cursorSpeed() / 60f;  // px per tick at 60 Hz
                cursorX = clamp(cursorX + dx * step, 0, getWidth() - 1);
                cursorY = clamp(cursorY + dy * step, 0, getHeight() - 1);
                mouse(0, MOVE, cursorX, cursorY);
                invalidate();
            }
            handler.postDelayed(this, 16);
        }
    };
    private long lastTapTime;
    private boolean longPressFired, moved;
    private float clickX, clickY;
    private int clickButton;
    private Runnable clickDown, clickUp;
    private Runnable dragPress;
    private boolean dragPressed;
    private final Runnable longPress = this::onLongPress;
    private final Runnable leftDown = this::startLeft;

    // ---- overlay ----
    private boolean panelOpen;
    private boolean panelMore;  // the settings panel shows its second page: the controls
    private final RectF panel = new RectF(), panelClose = new RectF(), panelSupport = new RectF(), panelGitHub = new RectF(),
            panelCloseBig = new RectF(), panelMoreButton = new RectF(), panelBack = new RectF();
    // the safe-zone frame editor: margins in dp, dragged in from any edge or scaled with a pinch
    private boolean safeEdit;
    private int safeDp;
    private float safeStartSpan;
    private int safeStartDp;
    private boolean safeDrag;
    private final RectF safeDone = new RectF(), safeReset = new RectF();
    private final Runnable fpsTick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (sShowFps.value() == 1) handler.postDelayed(this, 500);
        }
    };

    /** Frames per second over the last second (libmain.so, d3d9.cpp). */
    static native float nativeFps();

    public SporeControls(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("controls", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_v3", false)) {
            // the first build with Auto: the old manual values (a letterbox preset, a dragged safe margin) are
            // undone once, so an update never starts with a shrunken or wrongly shaped picture
            prefs.edit().putInt("safe_zone", AUTO).putBoolean("auto_v3", true).apply();
            writeIni("screen_fit", AUTO);
        }
        for (Setting s : settings) {
            if (s.codes != null) {
                String code = readIniString(s.key, s.codes[0]).toLowerCase(java.util.Locale.ROOT);
                for (int i = 0; i < s.codes.length; ++i) if (s.codes[i].equals(code)) s.index = i;
                continue;
            }
            int v = s.engine || s.ini ? readIni(s.key, s.value()) : prefs.getInt(s.key, s.value());
            for (int i = 0; i < s.values.length; ++i) if (s.values[i] == v) s.index = i;
        }
        for (int i = 0; i < settingRects.length; ++i) settingRects[i] = new RectF();
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        safeDp = prefs.getInt("safe_zone", AUTO);
        handleFx = prefs.getFloat("ui_handle_x", 0.5f);
        handleFy = prefs.getFloat("ui_handle_y", 0.0f);
        applyScreen(context);
        buttons.add(new Button("Esc", () -> key(KeyEvent.KEYCODE_ESCAPE)));
        buttons.add(new Button("Controls", () -> {
            if (stick == null) return;
            boolean show = stick.getVisibility() != View.VISIBLE;
            if (show) stick.setVisibility(View.VISIBLE);
            else stick.hide();
            prefs.edit().putBoolean("stick3_" + stage.name(), show).apply();
            invalidate();
        }));
        buttons.add(new Button("Stages", this::unlockStages));
        buttons.add(new Button("⌨", this::toggleKeyboard));
        buttons.add(new Button("⚙", () -> {
            panelOpen = true;
            panelMore = false;
            engineChanged = false;
            invalidate();
        }));
        if (sShowFps.value() == 1) handler.post(fpsTick);
        handler.postDelayed(modeTick, 2000);
    }

    // ---- input to SDL ----
    // where the game's surface starts on the screen (the Screen setting): touches are in screen pixels
    private static float surfaceX, surfaceY;

    private static void mouse(int buttons, int action, float x, float y) {
        SDLActivity.onNativeMouse(buttons, action, x - surfaceX, y - surfaceY, false);
    }

    private static void key(int keycode) {
        SDLActivity.onNativeKeyDown(keycode);
        SDLActivity.onNativeKeyUp(keycode);
    }

    /**
     * Opens every stage for a new game (normally a stage is playable only after reaching it): Spore's own cheat
     * console (Ctrl+Shift+C) with "levels -unlock", typed as text. Meant for the galaxy / main menu.
     */
    private void unlockStages() {
        // Ctrl and Shift stay down while the game handles the C (sent at once, the game saw a plain C: Spore's
        // screenshot key), then the command is typed as text.
        Runnable comboDown = () -> {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_CTRL_LEFT);
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT);
        };
        Runnable comboUp = () -> {
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_CTRL_LEFT);
        };
        handler.post(comboDown);
        handler.postDelayed(() -> SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_C), 150);
        handler.postDelayed(() -> SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_C), 350);
        handler.postDelayed(comboUp, 500);
        handler.postDelayed(() -> org.libsdl.app.SporeText.type("levels -unlock"), 1000);
        handler.postDelayed(() -> SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ENTER), 1400);
        handler.postDelayed(() -> SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ENTER), 1550);
        // close the console the same way
        handler.postDelayed(comboDown, 2200);
        handler.postDelayed(() -> SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_C), 2350);
        handler.postDelayed(() -> SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_C), 2550);
        handler.postDelayed(comboUp, 2700);
        android.widget.Toast.makeText(getContext(), "All stages unlocked (cheat console: levels -unlock)",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** Ctrl+Z: undo in the editors. */
    private void undo() {
        combo(KeyEvent.KEYCODE_Z);
    }

    /** Ctrl+Y: redo in the editors. */
    private void redo() {
        combo(KeyEvent.KEYCODE_Y);
    }

    /**
     * Ctrl with one key, spaced over several frames: the game reads SDL's modifier state when it handles the queued
     * key message, so Ctrl pressed and released in the same call is already up by then.
     */
    private void combo(int keycode) {
        handler.post(() -> SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_CTRL_LEFT));
        handler.postDelayed(() -> SDLActivity.onNativeKeyDown(keycode), 120);
        handler.postDelayed(() -> SDLActivity.onNativeKeyUp(keycode), 220);
        handler.postDelayed(() -> SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_CTRL_LEFT), 300);
    }

    /** Sizes the game's surface for the Screen and Safe zone settings, before it is first laid out. */
    private void applyScreen(Context context) {
        if (!(context instanceof SporeActivity)) return;
        SporeActivity a = (SporeActivity) context;
        android.view.View surface = a.surface();
        if (surface == null) return;
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();  // the window, not the panel
        int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        int[] in = insetSafe(a);  // cutout, rounded corners: the system's own safe area
        int left = in[0], top = in[1], right = in[2], bottom = in[3];
        int mode = sScreen.value();
        int availW = Math.max(1, w - left - right), availH = Math.max(1, h - top - bottom);
        if (mode == 1 || mode >= 3) {
            // a fixed aspect ratio: fitted into the safe area, the rest is left black on both sides
            float ratio = mode == 1 ? 16f / 9f : mode == 3 ? 4f / 3f : mode == 4 ? 16f / 10f : 21f / 9f;
            int fw = Math.round(availH * ratio), fh = availH;
            if (fw > availW) {
                fw = availW;
                fh = Math.round(availW / ratio);
            }
            int bx = (availW - fw) / 2, by = (availH - fh) / 2;
            left += bx;
            right += bx;
            top += by;
            bottom += by;
        } else if (mode == 2 && left == 0 && right == 0) {
            left = right = Math.round(32 * density);  // no system insets to read: a small guess
        }
        if (safeDp != AUTO) {  // a fixed margin picked in the safe-zone editor (Auto adds nothing extra)
            int safe = Math.round(safeDp * density);
            left += safe;
            right += safe;
            top += safe;
            bottom += safe;
        }
        android.widget.RelativeLayout.LayoutParams lp =
                new android.widget.RelativeLayout.LayoutParams(w - left - right, h - top - bottom);
        lp.leftMargin = left;
        lp.topMargin = top;
        surface.setLayoutParams(lp);
        surfaceX = left;
        surfaceY = top;
        android.util.Log.i("sporere", "screen " + mode + " safe " + safeDp + ": " + (w - left - right) + "x"
                + (h - top - bottom) + " at " + left + "," + top);
    }

    /** The system's safe insets: camera cutout plus rounded corners (0 when unknown). */
    private static int[] insetSafe(SporeActivity a) {
        int left = 0, top = 0, right = 0, bottom = 0;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsets in = a.getWindowManager().getCurrentWindowMetrics().getWindowInsets();
            android.view.DisplayCutout cut = in.getDisplayCutout();
            if (cut != null) {
                left = cut.getSafeInsetLeft();
                right = cut.getSafeInsetRight();
                top = cut.getSafeInsetTop();
                bottom = cut.getSafeInsetBottom();
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                // a corner of radius r is clear of the image when the sides move in by r (landscape)
                left = Math.max(left, Math.max(radius(in, android.view.RoundedCorner.POSITION_TOP_LEFT),
                        radius(in, android.view.RoundedCorner.POSITION_BOTTOM_LEFT)));
                right = Math.max(right, Math.max(radius(in, android.view.RoundedCorner.POSITION_TOP_RIGHT),
                        radius(in, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)));
            }
        }
        return new int[] {left, top, right, bottom};
    }

    private static int radius(android.view.WindowInsets in, int pos) {
        android.view.RoundedCorner c = in.getRoundedCorner(pos);
        return c == null ? 0 : c.getRadius();
    }

    private static void wheel(float steps) {
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, 0, steps, false);
    }

    /**
     * A click the game sees whichever way it reads the mouse. Menus use the window messages, but picking things in
     * the 3D world (stars in the galaxy, creatures, buildings) polls the button state once per frame: a press and
     * release within one frame never shows there. So the cursor moves first (hover), the button goes down a little
     * later and stays down for a few frames (150 ms: several frames even at a low frame rate).
     */
    private void click(float x, float y, int button) {
        cancelClick();
        mouse(0, MOVE, x, y);
        clickX = x;
        clickY = y;
        clickButton = button;
        clickDown = () -> mouse(clickButton, DOWN, clickX, clickY);
        clickUp = () -> mouse(0, UP, clickX, clickY);
        handler.postDelayed(clickDown, 60);
        handler.postDelayed(clickUp, 210);
    }

    /** Drops a click still in flight, so its late release cannot end the drag that started after it. */
    private void cancelClick() {
        if (clickDown != null) handler.removeCallbacks(clickDown);
        if (clickUp != null) handler.removeCallbacks(clickUp);
        clickDown = clickUp = null;
    }

    /** Drops the drag's delayed press (the finger lifted first); the button state stays as it was. */
    private void cancelDragPress() {
        if (dragPress != null) handler.removeCallbacks(dragPress);
        dragPress = null;
    }

    private void buzz(int kind) {
        if (sVibrate.value() == 1) performHapticFeedback(kind);
    }

    private float speed() {
        return sSpeed.value() / 100f;
    }

    /** Cursor cursor travel per finger drag (ui_cursor percent). */
    private float cursorSpeed() {
        return prefs.getInt("ui_cursor", 100) / 100f;
    }

    private boolean touchpad() {
        // The editors always use the cursor (precise part dragging). In the stages a touch acts where it lands, but
        // picking "Cursor" in the settings brings the cursor back for the rest of that stage.
        return sMode.value() == 1 && (stage == Stage.EDITOR || forceCursor);
    }

    /** The corner joystick: a second way to move the Cursor-mode pointer, sized and placed in the bottom right. */
    private float joyR() {
        return 46 * density;
    }

    private float joyCx() {
        return getWidth() - joyR() - 16 * density;
    }

    private float joyCy() {
        return getHeight() - joyR() - 16 * density;
    }

    private boolean joyVisible() {
        return touchpad() && !menuOpen && !panelOpen && !editLayout && !safeEdit;
    }

    private boolean joyHit(float x, float y) {
        return joyVisible() && Math.hypot(x - joyCx(), y - joyCy()) < joyR() * 1.35f;
    }

    // The keyboard types into the game through SDL's input connection (commitText, backspace, enter): without one
    // a keyboard opened on this view had nowhere to send its text (planet names did not change).
    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(
            android.view.inputmethod.EditorInfo out) {
        out.inputType = android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                | android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
        return org.libsdl.app.SporeText.connect(this);
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        setFocusableInTouchMode(true);
        requestFocus();
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    /** The pointer position a one-finger gesture acts at: the finger, or the touchpad cursor. */
    private float px(float x) {
        return touchpad() ? cursorX : x;
    }

    private float py(float y) {
        return touchpad() ? cursorY : y;
    }

    private void startLeft() {
        if (mode != Mode.PENDING) return;
        handler.removeCallbacks(longPress);
        cancelClick();  // a tap's delayed release must not end this drag a moment after it starts
        if (dragPansMap()) {
            mode = Mode.PAN;
            return;
        }
        if (dragFlies()) {
            mode = Mode.FLY;
            mouse(0, MOVE, lastX, lastY);
            mouse(RIGHT, DOWN, lastX, lastY);
            return;
        }
        if (dragTurnsCamera()) {
            mode = Mode.CAM_DRAG;
            camX = px(downX);
            camY = py(downY);
            mouse(0, MOVE, camX, camY);
            mouse(RIGHT, DOWN, camX, camY);
            return;
        }
        mode = Mode.LEFT_DRAG;
        dragPressed = false;
        cancelDragPress();
        mouse(0, MOVE, px(downX), py(downY));
        // the press one hover frame later: 3D picking (editor parts) reads where the cursor was on the frame before
        dragPress = () -> {
            if (mode != Mode.LEFT_DRAG) return;
            dragPressed = true;
            mouse(LEFT, DOWN, touchpad() ? cursorX : px(lastX), touchpad() ? cursorY : py(lastY));
        };
        handler.postDelayed(dragPress, 60);
    }

    private void onLongPress() {
        if (mode != Mode.PENDING || moved) return;
        handler.removeCallbacks(leftDown);
        longPressFired = true;
        mode = Mode.NONE;
        buzz(HapticFeedbackConstants.LONG_PRESS);
        click(px(downX), py(downY), RIGHT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        float x = e.getX(), y = e.getY();
        if (cursorX < 0) {
            cursorX = getWidth() / 2f;
            cursorY = getHeight() / 2f;
        }
        if (editLayout) {
            onEditTouch(action, x, y);
            return true;
        }
        if (safeEdit) {
            safeTouch(e, action, x, y);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            longPressFired = false;
            moved = false;
            if (panelOpen) {
                mode = Mode.PANEL;
                return true;
            }
            if (handle.contains(x, y)) {
                mode = Mode.HANDLE;
                handleDragged = false;
                downX = x;
                downY = y;
                return true;
            }
            Button b = menuOpen ? hitButton(x, y) : null;
            if (b == null) b = hitAction(x, y);
            if (b != null) {
                pressedButton = b;
                mode = Mode.BUTTON;
                return true;
            }
            if (joyHit(x, y)) {
                mode = Mode.JOY;
                joyDX = joyDY = 0;
                handler.post(joyTick);
                invalidate();
                return true;
            }
            downX = lastX = x;
            downY = lastY = y;
            long now = System.currentTimeMillis();
            boolean doubleTap = touchpad() && now - lastTapTime < DOUBLE_TAP_MS;
            if (doubleTap) {  // touchpad: double tap and hold = drag with the left button
                mode = Mode.PENDING;
                startLeft();
                return true;
            }
            mode = touchpad() && !dragTurnsCamera() ? Mode.CURSOR : Mode.PENDING;
            // direct mode: the cursor goes under the finger at once, so the game sees it hover over what will be
            // tapped for as long as the finger rests (3D picking needs a few frames of hover before the press)
            if (!touchpad()) mouse(0, MOVE, x, y);
            // press-and-hold drags with the left button after a moment (editors, cell); where a drag turns the
            // camera or moves the map it starts only when the finger moves, so a slow tap is still a tap
            if (mode == Mode.PENDING && !touchpad() && !dragTurnsCamera() && !dragPansMap() && !dragFlies())
                handler.postDelayed(leftDown, LEFT_DELAY_MS * 3);
            if (mode == Mode.PENDING && !touchpad() && dragFlies()) {
                handler.postDelayed(leftDown, 250);  // space: holding a finger still flies there too
            } else {
                handler.postDelayed(longPress, LONG_PRESS_MS);
            }
            return true;
        }
        if (mode == Mode.PANEL) {
            if (action == MotionEvent.ACTION_UP) panelTap(x, y);
            return true;
        }
        if (mode == Mode.HANDLE) {
            if (action == MotionEvent.ACTION_MOVE && (handleDragged || Math.hypot(x - downX, y - downY) > 12 * density)) {
                handleDragged = true;
                handleFx = clamp(x / getWidth(), 0, 1);
                handleFy = clamp(y / getHeight(), 0, 1);
                invalidate();
            } else if (action == MotionEvent.ACTION_UP) {
                if (handleDragged) {
                    prefs.edit().putFloat("ui_handle_x", handleFx).putFloat("ui_handle_y", handleFy).apply();
                } else {
                    menuOpen = !menuOpen;
                    buzz(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                mode = Mode.NONE;
                invalidate();
            }
            return true;
        }
        if (mode == Mode.BUTTON) {
            if (action == MotionEvent.ACTION_UP) {
                if (pressedButton != null && pressedButton.rect.contains(x, y)) {
                    buzz(HapticFeedbackConstants.VIRTUAL_KEY);
                    pressedButton.action.run();
                    // the menu stays open (Esc is often pressed twice in a row); the handle closes it, the gear
                    // opens the settings over it
                    invalidate();
                }
                pressedButton = null;
                mode = Mode.NONE;
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mode == Mode.JOY) return true;  // a second finger must not take the joystick over
                if (e.getPointerCount() == 2) {
                    handler.removeCallbacks(leftDown);
                    handler.removeCallbacks(longPress);
                    cancelDragPress();
                    if (mode == Mode.LEFT_DRAG || mode == Mode.CAM_DRAG || mode == Mode.FLY) mouse(0, UP, lastMidX, lastMidY);
                    dragPressed = false;
                    panKeys(0, 0);
                    mode = Mode.TWO_FINGER;
                    lastMidX = (e.getX(0) + e.getX(1)) / 2;
                    lastMidY = (e.getY(0) + e.getY(1)) / 2;
                    camX = getWidth() / 2f;
                    camY = getHeight() / 2f;
                    lastSpan = span(e);
                    wheelAccum = 0;
                    mouse(0, MOVE, camX, camY);
                    mouse(turnButton(), DOWN, camX, camY);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                onMove(e, x, y);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == Mode.TWO_FINGER) {
                    mouse(0, UP, camX, camY);
                    mode = Mode.NONE;  // the remaining finger does nothing until lifted
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                handler.removeCallbacks(leftDown);
                cancelDragPress();
                if (!longPressFired && !moved && (mode == Mode.PENDING || mode == Mode.CURSOR)) {
                    int button = rightTaps ? RIGHT : LEFT;
                    click(px(x), py(y), button);
                    lastTapTime = System.currentTimeMillis();
                    buzz(HapticFeedbackConstants.KEYBOARD_TAP);
                } else if (mode == Mode.LEFT_DRAG) {
                    if (dragPressed) mouse(0, UP, px(x), py(y));
                    boxArmed = false;  // the Select button arms one box drag
                } else if (mode == Mode.CAM_DRAG || mode == Mode.TWO_FINGER) {
                    mouse(0, UP, camX, camY);
                } else if (mode == Mode.PAN) {
                    panKeys(0, 0);
                } else if (mode == Mode.FLY) {
                    mouse(0, UP, x, y);
                } else if (mode == Mode.JOY) {
                    handler.removeCallbacks(joyTick);
                    joyDX = joyDY = 0;
                }
                if (!touchpad()) parkIfAtEdge(x, y);
                dragPressed = false;
                mode = Mode.NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /**
     * In the tribal, civilization and space stages the camera scrolls while the mouse rests at a screen edge. With a
     * finger the "mouse" stays where it was lifted, so after a tap near an edge the camera kept sliding: the cursor
     * goes back to the middle of the screen instead.
     */
    private void parkIfAtEdge(float x, float y) {
        float mx = getWidth() * 0.06f, my = getHeight() * 0.08f;
        if (x > mx && x < getWidth() - mx && y > my && y < getHeight() - my) return;
        handler.postDelayed(() -> mouse(0, MOVE, getWidth() / 2f, getHeight() / 2f), 120);
    }

    private void onMove(MotionEvent e, float x, float y) {
        float dx = x - lastX, dy = y - lastY;
        if (!moved && Math.hypot(x - downX, y - downY) > 10 * density) {
            moved = true;
            handler.removeCallbacks(longPress);
        }
        switch (mode) {
            case TWO_FINGER: {
                if (e.getPointerCount() < 2) return;
                float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                camX += (mx - lastMidX) * speed();
                camY += (my - lastMidY) * speed();
                lastMidX = mx;
                lastMidY = my;
                mouse(turnButton(), MOVE, camX, camY);
                float s = span(e);
                wheelAccum += (s - lastSpan) / (40 * density);  // one wheel step per 40 dp of pinch
                lastSpan = s;
                while (Math.abs(wheelAccum) >= 1) {
                    float step = Math.signum(wheelAccum);
                    wheel(step);
                    wheelAccum -= step;
                }
                break;
            }
            case CURSOR:
                cursorX = clamp(cursorX + dx * cursorSpeed(), 0, getWidth() - 1);
                cursorY = clamp(cursorY + dy * cursorSpeed(), 0, getHeight() - 1);
                mouse(0, MOVE, cursorX, cursorY);
                invalidate();
                break;
            case JOY: {
                float r = joyR() * 0.6f;
                float jx = x - joyCx(), jy = y - joyCy();
                float d = (float) Math.hypot(jx, jy);
                if (d > r) {
                    jx = jx / d * r;
                    jy = jy / d * r;
                }
                joyDX = jx;
                joyDY = jy;
                invalidate();
                break;
            }
            case PENDING:
                if (moved) {
                    handler.removeCallbacks(leftDown);
                    startLeft();
                }
                break;
            case LEFT_DRAG:
                if (touchpad()) {
                    cursorX = clamp(cursorX + dx * cursorSpeed(), 0, getWidth() - 1);
                    cursorY = clamp(cursorY + dy * cursorSpeed(), 0, getHeight() - 1);
                    mouse(dragPressed ? LEFT : 0, MOVE, cursorX, cursorY);
                    invalidate();
                } else {
                    mouse(dragPressed ? LEFT : 0, MOVE, x, y);
                }
                break;
            case CAM_DRAG:
                camX += dx * speed();
                camY += dy * speed();
                mouse(RIGHT, MOVE, camX, camY);
                break;
            case PAN:
                panKeys(x - downX, y - downY);
                break;
            case FLY:
                mouse(RIGHT, MOVE, x, y);
                break;
            default:
                break;
        }
        lastX = x;
        lastY = y;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private Button hitButton(float x, float y) {
        for (Button b : buttons) if (b.rect.contains(x, y)) return b;
        return null;
    }

    // ---- settings panel ----
    private void closePanel() {
        panelOpen = false;
        panelMore = false;
        if (engineChanged) {
            android.widget.Toast.makeText(getContext(), ru() ? "Настройки со * применятся после перезапуска игры"
                    : "Restart the game to apply the * settings", android.widget.Toast.LENGTH_LONG).show();
        }
        invalidate();
    }

    private void panelTap(float x, float y) {
        if (panelClose.contains(x, y) || panelCloseBig.contains(x, y) || !panel.contains(x, y)) {
            closePanel();
            return;
        }
        if (panelMore) {  // the controls page: back to the graphics, or step a setting
            if (panelBack.contains(x, y)) {
                panelMore = false;
                invalidate();
                return;
            }
            tapSetting(x, y, controlsSettings);
            return;
        }
        if (panelSupport.contains(x, y)) {
            SetupActivity.openSupport(getContext());
            return;
        }
        if (panelGitHub.contains(x, y)) {
            SetupActivity.openGitHub(getContext());
            return;
        }
        if (panelMoreButton.contains(x, y)) {
            panelMore = true;
            invalidate();
            return;
        }
        if (panelLayout.contains(x, y)) {
            closePanel();
            startLayoutEditor();
            return;
        }
        if (tapSetting(x, y, graphicsLeft)) return;
        tapSetting(x, y, graphicsRight);
    }

    /** Steps the setting whose row under the tap belongs to `shown`; true if a row was hit. */
    private boolean tapSetting(float x, float y, Setting[] shown) {
        for (Setting s : shown) {
            int i = java.util.Arrays.asList(settings).indexOf(s);
            RectF r = settingRects[i];
            if (!r.contains(x, y)) continue;
            if (s == sSafe) {  // not a list of values: open the frame editor
                startSafeEdit();
                return true;
            }
            // the value sits in the right part of the row between arrows: the left arrow steps back
            boolean back = x < r.right - r.width() * 0.22f && x > r.right - r.width() * 0.48f;
            int n = s.values.length;
            s.index = back ? (s.index + n - 1) % n : (s.index + 1) % n;
            buzz(HapticFeedbackConstants.KEYBOARD_TAP);
            applySetting(s);
            invalidate();
            return true;
        }
        return false;
    }

    private void applySetting(Setting s) {
        if (s.engine) {
            if (s.codes != null) {
                writeIniString(s.key, s.codes[s.index]);
                // the game folder's file wins: update its line too if it has one
                File game = gameIniFile();
                if (game.exists() && readIniFrom(game, s.key) != null) writeIniTo(game, s.key, s.codes[s.index]);
            }
            else writeIni(s.key, s.value());
            // Quality also picks the texture detail: Pretty loads full textures, the others skip the top mip
            if (s == sQuality) writeIni("texture_skip_mips", s.value() == 2 ? 0 : 1);
            engineChanged = true;
            return;
        }
        if (s.ini) writeIni(s.key, s.value());
        prefs.edit().putInt(s.key, s.value()).apply();
        if (s == sFps) {
            try {
                nativeSetFpsLimit(s.value());
            } catch (UnsatisfiedLinkError ignored) {
                // applies on the next start
            }
        }
        if (s == sShowFps && s.value() == 1) handler.post(fpsTick);
        if ((s == sScale || s == sAlpha) && stick != null) stick.applyLayout();
        if (s == sMode) {  // Cursor picked here: the cursor comes back even inside a stage
            forceCursor = s.value() == 1;
            if (forceCursor && cursorX < 0) {
                cursorX = getWidth() / 2f;
                cursorY = getHeight() / 2f;
            }
            invalidate();
        }
    }

    // ---- safe-zone frame editor ----
    private void startSafeEdit() {
        panelOpen = false;
        panelMore = false;
        safeEdit = true;
        safeDrag = false;
        safeStartSpan = 0;
        safeStartDp = previewSafeDp();
        invalidate();
    }

    /** The frame the editor shows: the picked margin, or where the system's safe area is when on Auto. */
    private int previewSafeDp() {
        if (safeDp != AUTO) return safeDp;
        if (!(getContext() instanceof SporeActivity)) return 0;
        int[] in = insetSafe((SporeActivity) getContext());
        int m = Math.max(Math.max(in[0], in[2]), Math.max(in[1], in[3]));
        return Math.round(m / density);
    }

    /** The margin being previewed; nothing is resized until Done, so a wrong drag cannot corrupt the picture. */
    private void setSafeDp(int dp) {
        int max = Math.round(Math.min(getWidth(), getHeight()) * 0.25f / density);
        safeDp = Math.max(0, Math.min(max, dp));
        invalidate();
    }

    /** One finger drags the nearest screen edge (the frame follows inward), a pinch scales it. */
    private void safeTouch(MotionEvent e, int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (safeDone.contains(x, y)) {
                prefs.edit().putInt("safe_zone", safeDp).apply();
                // resizing the surface under a running swap chain leaves the picture in a corner: size it on start
                android.widget.Toast.makeText(getContext(),
                        ru() ? "Безопасные края применятся после перезапуска игры"
                                : "Restart the game to apply the safe zone",
                        android.widget.Toast.LENGTH_LONG).show();
                safeEdit = false;
                safeStartSpan = 0;
                invalidate();
                return;
            }
            if (safeReset.contains(x, y)) {
                safeDp = AUTO;
                invalidate();
                return;
            }
            safeStartDp = previewSafeDp();
            safeStartSpan = 0;
            safeDrag = true;
            return;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            safeStartSpan = span(e);
            safeStartDp = previewSafeDp();
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (e.getPointerCount() >= 2) {
                // pinch out (zoom in) grows the picture, so the margin shrinks
                if (safeStartSpan > 0) setSafeDp(safeStartDp - Math.round((span(e) - safeStartSpan) / (2 * density)));
            } else if (safeDrag) {
                // the margin is the distance to the nearest edge: drag that border inward or outward
                float w = getWidth(), h = getHeight();
                float m = Math.min(Math.min(x, w - x), Math.min(y, h - y));
                setSafeDp(Math.round(m / density));
            }
            return;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            safeStartSpan = 0;  // the remaining finger must not jump the frame
            return;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            safeDrag = false;
            safeStartSpan = 0;
        }
    }

    private void drawSafeEdit(Canvas c) {
        float w = getWidth(), h = getHeight();
        int s = Math.round(previewSafeDp() * density);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x99000000);
        c.drawRect(0, 0, w, s, fill);
        c.drawRect(0, h - s, w, h, fill);
        c.drawRect(0, s, s, h - s, fill);
        c.drawRect(w - s, s, w, h - s, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(3 * density);
        fill.setColor(TEAL);
        c.drawRect(new RectF(s, s, w - s, h - s), fill);
        // the four edges can be grabbed; a pinch works anywhere
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(TEAL);
        float hw = 34 * density, hh = 9 * density;
        c.drawRoundRect(new RectF(w / 2 - hw, s - hh / 2, w / 2 + hw, s + hh / 2), hh / 2, hh / 2, fill);
        c.drawRoundRect(new RectF(w / 2 - hw, h - s - hh / 2, w / 2 + hw, h - s + hh / 2), hh / 2, hh / 2, fill);
        c.drawRoundRect(new RectF(s - hh / 2, h / 2 - hw, s + hh / 2, h / 2 + hw), hh / 2, hh / 2, fill);
        c.drawRoundRect(new RectF(w - s - hh / 2, h / 2 - hw, w - s + hh / 2, h / 2 + hw), hh / 2, hh / 2, fill);
        // title pill and the two buttons
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(15 * density);
        String title = (ru() ? "Безопасные края: " : "Safe zone: ") + (safeDp == AUTO ? "Auto" : safeDp + " dp");
        String how = ru() ? "тяните край или сведите пальцы" : "drag an edge or pinch";
        float tw = Math.max(text.measureText(title), text.measureText(how)) + 36 * density;
        float top = 10 * density;
        RectF pill = new RectF((w - tw) / 2, top, (w + tw) / 2, top + 50 * density);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xf20b1626);
        c.drawRoundRect(pill, 14 * density, 14 * density, fill);
        text.setColor(Color.WHITE);
        c.drawText(title, pill.centerX(), pill.top + 21 * density, text);
        text.setTextSize(11 * density);
        text.setColor(0xff8aa8b8);
        c.drawText(how, pill.centerX(), pill.top + 38 * density, text);
        float bw = 130 * density, bh = 40 * density, gap = 10 * density, by = h - 16 * density - bh;
        float left = (w - bw * 2 - gap) / 2;
        safeReset.set(left, by, left + bw, by + bh);
        safeDone.set(safeReset.right + gap, by, safeReset.right + gap + bw, by + bh);
        text.setTextSize(14 * density);
        fill.setColor(0xff6a3a3a);
        c.drawRoundRect(safeReset, 12 * density, 12 * density, fill);
        fill.setColor(0xff1d7a6a);
        c.drawRoundRect(safeDone, 12 * density, 12 * density, fill);
        text.setColor(Color.WHITE);
        c.drawText(ru() ? "Сброс" : "Reset", safeReset.centerX(), safeReset.centerY() + 5 * density, text);
        c.drawText(ru() ? "Готово" : "Done", safeDone.centerX(), safeDone.centerY() + 5 * density, text);
        text.setTextSize(14 * density);
    }

    // ---- layout editor ----
    private void startLayoutEditor() {
        editLayout = true;
        menuOpen = false;
        selected = null;
        SporeStick.editing = true;
        if (stick != null) stick.invalidate();
        invalidate();
    }

    private void stopLayoutEditor() {
        editLayout = false;
        selected = null;
        SporeStick.editing = false;
        if (stick != null) stick.applyLayout();
        invalidate();
    }

    /** The editable elements and their places (pixels) right now: id -> {x, y, radius}. */
    private List<Object[]> editElements() {
        List<Object[]> out = new ArrayList<>();
        float w = getWidth(), h = getHeight(), sc = scale();
        out.add(new Object[] {"handle", handleFx * w, handleFy * h, 22 * density * sc * sizeOf("handle"), "☰"});
        out.add(new Object[] {"stick", layX("stick", SporeStick.STICK_X) * w, layY("stick", SporeStick.STICK_Y) * h,
                62 * density * sc * sizeOf("stick"), ru() ? "Стик" : "Stick"});
        out.add(new Object[] {"jump", layX("jump", SporeStick.JUMP_X) * w, layY("jump", SporeStick.JUMP_Y) * h,
                36 * density * sc * sizeOf("jump"), ru() ? "Прыжок" : "Jump"});
        for (int i = 0; i < SLOT_IDS.length; ++i) {
            String label = i < actions.size() ? actions.get(i).label : String.valueOf(i + 1);
            out.add(new Object[] {SLOT_IDS[i], layX(SLOT_IDS[i], SLOT_DEFAULTS[i][0]) * w,
                    layY(SLOT_IDS[i], SLOT_DEFAULTS[i][1]) * h, 29 * density * sc * sizeOf(SLOT_IDS[i]), label});
        }
        return out;
    }

    private void onEditTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            editing = null;
            resizing = false;
            if (editDone.contains(x, y)) {
                stopLayoutEditor();
                return;
            }
            if (editReset.contains(x, y)) {
                SharedPreferences.Editor e = prefs.edit();
                for (String k : prefs.getAll().keySet()) if (k.startsWith("lay_")) e.remove(k);
                e.putFloat("ui_handle_x", 0.5f).putFloat("ui_handle_y", 0f).apply();
                handleFx = 0.5f;
                handleFy = 0f;
                selected = null;
                buzz(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
                return;
            }
            // the size bar under the picked control: drag right to grow it, left to shrink it
            if (selected != null && x >= editSizeBar.left - 8 * density && x <= editSizeBar.right + 8 * density
                    && y >= editSizeBar.top - 8 * density && y <= editSizeBar.bottom + 8 * density) {
                resizing = true;
                setSizeFromBar(x);
                return;
            }
            float best = Float.MAX_VALUE;
            String hit = null;
            for (Object[] el : editElements()) {
                float d = (float) Math.hypot(x - (float) el[1], y - (float) el[2]);
                if (d < (float) el[3] * 1.3f + 8 * density && d < best) {
                    best = d;
                    hit = (String) el[0];
                }
            }
            selected = hit;  // a tap on empty space clears the selection
            editing = hit;
            invalidate();
            return;
        }
        if (resizing) {
            if (action == MotionEvent.ACTION_MOVE) setSizeFromBar(x);
            else resizing = false;
            return;
        }
        if (editing == null) return;
        float fx = clamp(x / getWidth(), 0.02f, 0.98f), fy = clamp(y / getHeight(), 0.02f, 0.98f);
        if (editing.equals("handle")) {
            handleFx = fx;
            handleFy = fy;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (!editing.equals("handle"))
                prefs.edit().putFloat("lay_" + editing + "_x", fx).putFloat("lay_" + editing + "_y", fy).apply();
            invalidate();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (editing.equals("handle"))
                prefs.edit().putFloat("ui_handle_x", handleFx).putFloat("ui_handle_y", handleFy).apply();
            editing = null;
            invalidate();
        }
    }

    /** The size bar: its left end is half size, its right end double size. */
    private void setSizeFromBar(float x) {
        if (selected == null || editSizeBar.width() <= 0) return;
        float f = SIZE_MIN + (SIZE_MAX - SIZE_MIN) * clamp((x - editSizeBar.left) / editSizeBar.width(), 0f, 1f);
        f = Math.round(f * 20) / 20f;  // 5% steps
        prefs.edit().putFloat("lay_" + selected + "_size", f).apply();
        if (stick != null) stick.applyLayout();
        invalidate();
    }

    private void drawEditor(Canvas c) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x66000000);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        text.setTextAlign(Paint.Align.CENTER);
        for (Object[] el : editElements()) {
            float x = (float) el[1], y = (float) el[2], r = (float) el[3];
            boolean active = el[0].equals(selected);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(active ? 0xc040d6c8 : 0x900a1a2a);
            c.drawCircle(x, y, r, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(2 * density);
            fill.setColor(TEAL);
            c.drawCircle(x, y, r, fill);
            text.setColor(Color.WHITE);
            text.setTextSize(14 * density);
            c.drawText((String) el[4], x, y + 5 * density, text);
        }
        // the bar: what to do, Done and Reset
        float bw = 130 * density, bh = 40 * density, top = 12 * density, gap = 10 * density;
        String title = ru() ? "Перетаскивайте кнопки" : "Drag the controls";
        text.setTextSize(15 * density);
        float tw = text.measureText(title) + 30 * density;
        float total = tw + 2 * (bw + gap), left = (getWidth() - total) / 2;
        RectF t = new RectF(left, top, left + tw, top + bh);
        editReset.set(t.right + gap, top, t.right + gap + bw, top + bh);
        editDone.set(editReset.right + gap, top, editReset.right + gap + bw, top + bh);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xe00b1626);
        c.drawRoundRect(t, 12 * density, 12 * density, fill);
        fill.setColor(0xff6a3a3a);
        c.drawRoundRect(editReset, 12 * density, 12 * density, fill);
        fill.setColor(0xff1d7a6a);
        c.drawRoundRect(editDone, 12 * density, 12 * density, fill);
        text.setColor(Color.WHITE);
        c.drawText(title, t.centerX(), t.centerY() + 5 * density, text);
        c.drawText(ru() ? "Сброс" : "Reset", editReset.centerX(), editReset.centerY() + 5 * density, text);
        c.drawText(ru() ? "Готово" : "Done", editDone.centerX(), editDone.centerY() + 5 * density, text);
        // size bar for the picked control, right under Reset: its left end is half size, the right end double
        if (selected != null) {
            float barW = Math.min(getWidth() * 0.3f, 260 * density), barH = 34 * density;
            float cx = clamp(editReset.centerX(), 8 * density + barW / 2, getWidth() - 8 * density - barW / 2);
            float barTop = editReset.bottom + 10 * density;
            editSizeBar.set(cx - barW / 2, barTop, cx + barW / 2, barTop + barH);
            float f = clamp((sizeOf(selected) - SIZE_MIN) / (SIZE_MAX - SIZE_MIN), 0f, 1f);
            float kx = editSizeBar.left + editSizeBar.width() * f;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xe00b1626);
            c.drawRoundRect(editSizeBar, barH / 2, barH / 2, fill);
            fill.setColor(0xaa40d6c8);
            c.drawRoundRect(new RectF(editSizeBar.left, editSizeBar.top, kx, editSizeBar.bottom),
                    barH / 2, barH / 2, fill);
            fill.setColor(Color.WHITE);
            c.drawCircle(kx, editSizeBar.centerY(), barH * 0.4f, fill);
            text.setTextSize(13 * density);
            text.setColor(Color.WHITE);
            c.drawText((ru() ? "Размер" : "Size") + " " + Math.round(sizeOf(selected) * 100) + "%",
                    editSizeBar.centerX(), editSizeBar.bottom + 17 * density, text);
        } else {
            text.setTextSize(13 * density);
            text.setColor(0xffc8d8e8);
            c.drawText(ru() ? "Выберите кнопку, затем тяните полосу" : "Tap a control, then drag the bar",
                    getWidth() / 2f, getHeight() - 16 * density, text);
        }
        text.setTextSize(14 * density);
    }

    private File iniFile() {
        File dir = getContext().getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "sporere.ini");
    }

    private String readIniString(String key, String def) {
        String v = readIniFrom(gameIniFile(), key);  // the game folder's file wins, as in the game
        if (v == null) v = readIniFrom(iniFile(), key);
        return v != null ? v : def;
    }

    private static String readIniFrom(File ini, String key) {
        if (ini == null) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(ini))) {
            for (String l; (l = r.readLine()) != null;) {
                String[] kv = l.split("=", 2);
                if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(key)) return kv[1].trim();
            }
        } catch (IOException ignored) {
            // no setting yet
        }
        return null;
    }

    private int readIni(String key, int def) {
        File ini = iniFile();
        if (ini == null) return def;
        try (BufferedReader r = new BufferedReader(new FileReader(ini))) {
            for (String l; (l = r.readLine()) != null;) {
                String[] kv = l.split("=", 2);
                if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(key)) return Integer.parseInt(kv[1].trim());
            }
        } catch (IOException | NumberFormatException ignored) {
            // no setting yet
        }
        return def;
    }

    private void writeIni(String key, int value) {
        writeIniString(key, String.valueOf(value));
    }

    private void writeIniString(String key, String value) {
        writeIniTo(iniFile(), key, value);
    }

    /** The game folder's sporere.ini (its keys win over the app's: a PC may have put a language there). */
    private File gameIniFile() {
        String game = null;
        try {
            game = android.system.Os.getenv("SPORERE_GAME");
        } catch (RuntimeException ignored) {
            // not set: the game is in the app's folder
        }
        File dir = game != null ? new File(game) : new File(getContext().getExternalFilesDir(null), "game");
        return new File(dir, "sporere.ini");
    }

    private void writeIniTo(File ini, String key, String value) {
        if (ini == null) return;
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(ini))) {
            for (String l; (l = r.readLine()) != null;) {
                if (!l.split("=", 2)[0].trim().equalsIgnoreCase(key)) lines.add(l);
            }
        } catch (IOException ignored) {
            // a new file
        }
        lines.add(key + " = " + value);
        try (FileWriter w = new FileWriter(ini)) {
            for (String l : lines) w.write(l + "\n");
        } catch (IOException ignored) {
            // the game keeps its defaults
        }
    }

    // ---- drawing ----
    @Override
    protected void onDraw(Canvas c) {
        float size = 44 * density * scale(), gap = 8 * density, r = size / 2 * sizeOf("handle");
        float hx = clamp(handleFx * getWidth(), r + 4 * density, getWidth() - r - 4 * density);
        float hy = clamp(handleFy * getHeight(), r + 4 * density, getHeight() - r - 4 * density);
        handle.set(hx - r, hy - r, hx + r, hy + r);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(alphaOf(menuOpen ? 0xd040d6c8 : 0x900a1a2a));
        if (!editLayout) c.drawCircle(hx, hy, r, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(2 * density);
        fill.setColor(TEAL);
        c.drawCircle(hx, hy, r, fill);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setTextSize(20 * density * sizeOf("handle"));
        c.drawText(menuOpen ? "\u00d7" : "\u2630", hx, hy + 7 * density * sizeOf("handle"), text);
        // state dots: camera lock, WASD stick
        boolean wasd = stick != null && stick.getVisibility() == View.VISIBLE;
        if (camLock || wasd) {
            fill.setColor(0xffffd24a);
            if (camLock) c.drawCircle(hx - r * 0.55f, hy - r * 0.75f, 4 * density, fill);
            if (wasd) c.drawCircle(hx + r * 0.55f, hy - r * 0.75f, 4 * density, fill);
        }
        // the menu row opens toward the wider side of the screen, beside the handle
        boolean toLeft = hx > getWidth() / 2f;
        float bx = toLeft ? hx - r - gap - size : hx + r + gap;
        float by = hy - r;
        text.setTextSize(14 * density);
        for (Button b : buttons) {
            if (!menuOpen) {
                b.rect.setEmpty();
                continue;
            }
            boolean controls = b.label.equals("Controls");
            float bw = controls ? size * 1.75f : size;  // "Controls" needs the wider button to fit
            b.rect.set(bx, by, bx + bw, by + size);
            boolean on = (b.label.equals("Cam") && camLock) || (controls && wasd);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(on ? 0xd040d6c8 : 0xc00a1a2a);
            c.drawRoundRect(b.rect, 12 * density, 12 * density, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(1.5f * density);
            fill.setColor(TEAL);
            c.drawRoundRect(b.rect, 12 * density, 12 * density, fill);
            fill.setStyle(Paint.Style.FILL);
            text.setTextSize((controls ? 11 : 14) * density);
            c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + 5 * density, text);
            bx += toLeft ? -(bw + gap) : bw + gap;
        }
        drawActions(c, size, gap);
        if (joyVisible()) drawJoy(c);
        float x = 8 * density;
        text.setColor(Color.WHITE);
        if (touchpad() && cursorX >= 0) {  // the touchpad cursor
            fill.setColor(0xccffffff);
            c.drawCircle(cursorX, cursorY, 7 * density, fill);
            fill.setColor(0xff102030);
            c.drawCircle(cursorX, cursorY, 3 * density, fill);
        }
        if (sShowFps.value() == 1) {
            text.setTextSize(13 * density);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.format(Locale.ROOT, "%.0f fps", nativeFps()), x, getHeight() - 10 * density, text);
            text.setTextAlign(Paint.Align.CENTER);
        }
        if (hint != null && !panelOpen) {
            text.setTextSize(15 * density);
            float w = Math.min(getWidth() - 32 * density, text.measureText(hint) + 40 * density);
            float top = 12 * density, h = 40 * density;
            RectF hr = new RectF((getWidth() - w) / 2, top, (getWidth() + w) / 2, top + h);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xd00a1a2a);
            c.drawRoundRect(hr, 14 * density, 14 * density, fill);
            text.setColor(Color.WHITE);
            c.drawText(hint, hr.centerX(), hr.centerY() + 5 * density, text);
            text.setTextSize(14 * density);
        }
        if (editLayout) drawEditor(c);
        if (panelOpen) drawPanel(c);
        if (safeEdit) drawSafeEdit(c);
    }

    /** The Cursor-mode joystick in the bottom right corner: the knob's offset from the center sets the speed. */
    private void drawJoy(Canvas c) {
        float r = joyR(), cx = joyCx(), cy = joyCy();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x600a1a2a);
        c.drawCircle(cx, cy, r, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(2 * density);
        fill.setColor(0x9040d6c8);
        c.drawCircle(cx, cy, r, fill);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xb040d6c8);
        c.drawCircle(cx + joyDX, cy + joyDY, r * 0.4f, fill);
    }

    /** The stage's action buttons, each in its layout slot (sized and faded per the settings). */
    private void drawActions(Canvas c, float size, float gap) {
        if (actions.isEmpty() || editLayout) return;
        for (int i = 0; i < actions.size() && i < SLOT_IDS.length; ++i) {
            Button b = actions.get(i);
            float f = sizeOf(SLOT_IDS[i]);
            float r = 29 * density * scale() * f;
            text.setTextSize(16 * density * scale() * f);
            float x = layX(SLOT_IDS[i], SLOT_DEFAULTS[i][0]) * getWidth();
            float y = layY(SLOT_IDS[i], SLOT_DEFAULTS[i][1]) * getHeight();
            b.rect.set(x - r, y - r, x + r, y + r);
            boolean on = ((b.label.equals("Order") || b.label.equals("Приказ")) && rightTaps)
                    || ((b.label.equals("Select") || b.label.equals("Выбор")) && boxArmed);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(alphaOf(on ? 0xd0ffb03a : 0x900a1a2a));
            c.drawOval(b.rect, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(2 * density);
            fill.setColor(alphaOf(on ? 0xffffb03a : 0xff40d6c8));
            c.drawOval(b.rect, fill);
            fill.setStyle(Paint.Style.FILL);
            text.setColor(alphaOf(0xffffffff));
            c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + 6 * density * scale() * f, text);
        }
        text.setTextSize(14 * density);
        text.setColor(Color.WHITE);
    }

    /**
     * The settings panel. Its main page holds the graphics settings only; "More" opens the second page with the
     * controls. Both pages are the same height, so switching pages does not resize the panel.
     */
    private void drawPanel(Canvas c) {
        float w = Math.min(getWidth() - 24 * density, 700 * density);
        float row = 36 * density, rowGap = 6 * density, pad = 16 * density, header = 56 * density;
        float section = 26 * density, footer = 62 * density;
        Setting[][] cols = panelMore
                ? new Setting[][] {{sLanguage, sVibrate, sSafe}, {sMode, sCursor, sSpeed, sScale, sAlpha}}
                : new Setting[][] {graphicsLeft, graphicsRight};
        String[] titles = panelMore
                ? new String[] {ru() ? "ОБЩИЕ" : "GENERAL", ru() ? "КАСАНИЯ" : "TOUCH"}
                : new String[] {ru() ? "ГРАФИКА" : "GRAPHICS", ""};
        int rows = Math.max(cols[0].length, cols[1].length);
        if (rows > 4) {  // the controls page has five rows: shorten them so the footer still fits
            row = 32 * density;
            rowGap = 5 * density;
        }
        float h = header + section + rows * (row + rowGap) + footer;
        float left = (getWidth() - w) / 2, top = Math.max(6 * density, (getHeight() - h) / 2);
        panel.set(left, top, left + w, top + h);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xf20b1626);
        c.drawRoundRect(panel, 20 * density, 20 * density, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(1.5f * density);
        fill.setColor(0xff2a5a6a);
        c.drawRoundRect(panel, 20 * density, 20 * density, fill);
        fill.setStyle(Paint.Style.FILL);
        // header: title left, the credit under it, a close cross right
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.WHITE);
        text.setTextSize(19 * density);
        c.drawText(panelMore ? (ru() ? "Управление" : "Controls") : (ru() ? "Настройки" : "Settings"),
                left + pad, top + 30 * density, text);
        text.setTextSize(11 * density);
        text.setColor(0xff8aa8b8);
        c.drawText(SetupActivity.CREDIT + (ru() ? "   ·   * — после перезапуска" : "   ·   * = after restart"),
                left + pad, top + 47 * density, text);
        float cs = 34 * density;
        panelClose.set(left + w - pad - cs, top + 12 * density, left + w - pad, top + 12 * density + cs);
        fill.setColor(0xff1a2c3c);
        c.drawRoundRect(panelClose, 10 * density, 10 * density, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        text.setTextSize(18 * density);
        c.drawText("×", panelClose.centerX(), panelClose.centerY() + 6 * density, text);
        // only the rows drawn on this page may be tapped
        for (RectF r : settingRects) r.setEmpty();
        float colW = (w - pad * 3) / 2;
        for (int col = 0; col < 2; ++col) {
            float cx = left + pad + col * (colW + pad), y = top + header;
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(11 * density);
            text.setColor(TEAL);
            c.drawText(titles[col], cx + 4 * density, y + 14 * density, text);
            y += section;
            for (Setting s : cols[col]) {
                int i = java.util.Arrays.asList(settings).indexOf(s);
                RectF rect = settingRects[i];
                rect.set(cx, y, cx + colW, y + row);
                fill.setColor(0xff142638);
                c.drawRoundRect(rect, 10 * density, 10 * density, fill);
                text.setTextAlign(Paint.Align.LEFT);
                text.setTextSize(13 * density);
                text.setColor(Color.WHITE);
                c.drawText(s.label() + (s.engine || s == sSafe ? " *" : ""), rect.left + 12 * density, rect.centerY() + 5 * density,
                        text);
                // ‹ value ›
                float vz = rect.width() * 0.48f, vl = rect.right - vz, mid = rect.right - vz / 2;
                text.setTextAlign(Paint.Align.CENTER);
                text.setColor(0xff6a8a9a);
                text.setTextSize(16 * density);
                c.drawText("‹", vl + 12 * density, rect.centerY() + 6 * density, text);
                c.drawText("›", rect.right - 12 * density, rect.centerY() + 6 * density, text);
                text.setColor(TEAL);
                text.setTextSize(13 * density);
                c.drawText(s == sSafe ? (safeDp == AUTO ? "Auto" : safeDp + " dp") : s.names[s.index], mid,
                        rect.centerY() + 5 * density, text);
                y += row + rowGap;
            }
        }
        // footer: on the main page the layout editor, "More" (the controls page), support, source code and close;
        // on the controls page back and close
        panelLayout.setEmpty();
        panelSupport.setEmpty();
        panelGitHub.setEmpty();
        panelMoreButton.setEmpty();
        panelBack.setEmpty();
        float by = top + h - footer + 12 * density, bh = 38 * density;
        RectF closeBig = new RectF();
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        text.setTextSize(14 * density);
        if (panelMore) {
            float bw = (w - pad * 3) / 2;
            panelBack.set(left + pad, by, left + pad + bw, by + bh);
            closeBig.set(panelBack.right + pad, by, panelBack.right + pad + bw, by + bh);
            fill.setColor(0xff1d5a6a);
            c.drawRoundRect(panelBack, 10 * density, 10 * density, fill);
            fill.setColor(0xff22384a);
            c.drawRoundRect(closeBig, 10 * density, 10 * density, fill);
            c.drawText(ru() ? "Назад" : "Back", panelBack.centerX(), panelBack.centerY() + 5 * density, text);
            c.drawText(ru() ? "Закрыть" : "Close", closeBig.centerX(), closeBig.centerY() + 5 * density, text);
        } else {
            float bw = (w - pad * 6) / 5;
            // five buttons on a narrow phone: 12dp keeps "Раскладка"/"Закрыть" inside their boxes
            text.setTextSize(12 * density);
            panelLayout.set(left + pad, by, left + pad + bw, by + bh);
            panelMoreButton.set(panelLayout.right + pad, by, panelLayout.right + pad + bw, by + bh);
            panelSupport.set(panelMoreButton.right + pad, by, panelMoreButton.right + pad + bw, by + bh);
            panelGitHub.set(panelSupport.right + pad, by, panelSupport.right + pad + bw, by + bh);
            closeBig.set(panelGitHub.right + pad, by, panelGitHub.right + pad + bw, by + bh);
            fill.setColor(0xff1d5a6a);
            c.drawRoundRect(panelLayout, 10 * density, 10 * density, fill);
            fill.setColor(0xff3a4a6a);
            c.drawRoundRect(panelMoreButton, 10 * density, 10 * density, fill);
            fill.setColor(0xff7a3a6e);
            c.drawRoundRect(panelSupport, 10 * density, 10 * density, fill);
            fill.setColor(0xff2d333b);
            c.drawRoundRect(panelGitHub, 10 * density, 10 * density, fill);
            fill.setColor(0xff22384a);
            c.drawRoundRect(closeBig, 10 * density, 10 * density, fill);
            c.drawText(ru() ? "Правка" : "Layout", panelLayout.centerX(), panelLayout.centerY() + 5 * density, text);
            c.drawText(ru() ? "Ещё" : "More", panelMoreButton.centerX(), panelMoreButton.centerY() + 5 * density, text);
            c.drawText("Patreon", panelSupport.centerX(), panelSupport.centerY() + 5 * density, text);
            c.drawText("GitHub", panelGitHub.centerX(), panelGitHub.centerY() + 5 * density, text);
            c.drawText(ru() ? "Закрыть" : "Close", closeBig.centerX(), closeBig.centerY() + 5 * density, text);
        }
        panelCloseBig.set(closeBig);
    }

    /** The system back button opens the game's menu, like Esc. */
    static boolean onBack(Activity a) {
        key(KeyEvent.KEYCODE_ESCAPE);
        return true;
    }
}
