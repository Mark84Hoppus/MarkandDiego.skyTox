package com.hqgame.networknes;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

public final class Settings {
    public static enum RemoteControl {
        NO_REMOTE_CONTROL,
        ENABLE_LAN_REMOTE_CONTROL,
        ENABLE_WIFI_DIRECT_REMOTE_CONTROL,
        CONNECT_LAN_REMOTE_CONTROL,
        ENABLE_INTERNET_REMOTE_CONTROL_FB,
        ENABLE_INTERNET_REMOTE_CONTROL_GOOGLE,
        ENABLE_INTERNET_REMOTE_CONTROL_PUBLIC,
        JOIN_INTERNET_REMOTE_CONTROL_FB,
        JOIN_INTERNET_REMOTE_CONTROL_GOOGLE,
        JOIN_INTERNET_REMOTE_CONTROL_PUBLIC,
        QUICKJOIN_INTERNET_REMOTE_CONTROL_GOOGLE;
    }

    public static enum Orientation {
        AUTO,
        LANDSCAPE,
        PORTRAIT;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    public static enum DisplayFilterMode {
        NONE,
        HQ2X,
        HQ4X,
        _2XBR,
        _4XBR,
        XBR_LV2_FAST_2X,
        XBR_LV2_FAST_4X,
        XBR_LV2_2X,
        XBR_LV2_4X,
        SCANLINE,
        CRT_HYLLIAN,
        CRT_CGWG_FAST;

        @Override
        public String toString() {
            switch (this) {
                case _2XBR:
                    return "2xbr";
                case _4XBR:
                    return "4xbr";
                case XBR_LV2_FAST_2X:
                    return "2xbr lv2 fast";
                case XBR_LV2_FAST_4X:
                    return "4xbr lv2 fast";
                case XBR_LV2_2X:
                    return "2xbr lv2";
                case XBR_LV2_4X:
                    return "4xbr lv2";
                case CRT_HYLLIAN:
                    return "crt hyllian";
                case CRT_CGWG_FAST:
                    return "crt cgwg fast";
                default:
                    return name().toLowerCase();
            }
        }
    }

    public static enum Button {
        A,
        B,
        SELECT,
        START,
        AB,
        LEFT,
        RIGHT,
        UP,
        DOWN,
        AUTO_A,
        AUTO_B,
        QUICK_SAVE,
        QUICK_LOAD,
        MENU,
        EXIT,
        CHAT;

        public static final int NORMAL_BUTTONS = 5;

        @Override
        public String toString() {
            switch (this) {
                case AUTO_A:
                    return "Auto A";
                case AUTO_B:
                    return "Auto B";
                case AB:
                    return "A + B";
                case QUICK_SAVE:
                    return "Quick Save";
                case QUICK_LOAD:
                    return "Quick Load";
                case MENU:
                    return "Menu";
                case EXIT:
                    return "Exit";
                case CHAT:
                    return "Chat";
                default:
                    return name();
            }
        }
    }

    private static float staticAudioVolume = 1.0f;
    private static boolean staticButtonsVibration = true;
    private static boolean staticVoiceChatEnabled = false;
    private static boolean staticUIButtonsEnabled = true;
    private static boolean staticFullscreenEnabled = false;
    private static Orientation staticOrientation = Orientation.PORTRAIT;
    private static boolean staticBtnATurbo = false;
    private static boolean staticBtnBTurbo = false;
    private static boolean staticDisableAds = true;
    private static boolean staticDisableAutoSearchGamesOnResume = true;
    private static DisplayFilterMode staticFilterMode = DisplayFilterMode.NONE;
    private static float staticUIButtonsOpacity = 0.65f;
    private static final TreeMap<Integer, HashSet<Button>> staticKey2ButtonMap = new TreeMap<>();
    private static final TreeMap<Button, Integer> staticButton2KeyMap = new TreeMap<>();
    private static final TreeMap<Button, Rect> staticPortraitButtonRects = new TreeMap<>();
    private static final TreeMap<Button, Rect> staticLandscapeButtonRects = new TreeMap<>();

    public byte[] rawData = new byte[0];
    public java.util.List<String> labels = Collections.emptyList();
    public float opacity = 0.65f;
    public boolean uiButtonsEnabled = true;
    public boolean turboEnabled = false;
    public boolean filteringEnabled = true;
    public boolean fullScreen = false;
    public Settings.d audioMode = Settings.d.b;
    public boolean audioEnabled = true;
    public boolean rewindEnabled = false;
    public boolean showFps = false;
    public boolean debugInput = false;
    public Settings.c shaderMode = Settings.c.b;
    public float volume = 1.0f;
    public TreeMap<String, String> options = new TreeMap<>();

    public Settings() {
    }

    public enum b {
        b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q;

        @Override
        public String toString() {
            return name();
        }
    }

    public enum c {
        b, c, d, e, f, g, h, i, j, k, l, m;

        @Override
        public String toString() {
            return name();
        }
    }

    public enum d {
        b, c, d;

        @Override
        public String toString() {
            return name();
        }
    }

    public enum e {
        b, c, d, e, f, g, h, i, j, k, l;

        public boolean b() {
            return this == c || this == e || this == g || this == i || this == k;
        }

        public boolean c() {
            return this == d || this == e || this == h || this == i || this == l;
        }

        public boolean d() {
            return this == f || this == g || this == h || this == i;
        }
    }

    public float a() {
        return volume;
    }

    public Rect a(b button, boolean landscape) {
        return defaultRect(button);
    }

    public Iterable<b> a(int ignored) {
        return Arrays.asList(Settings.b.values());
    }

    public String a(b button) {
        return button.toString();
    }

    public void a(float value) {
        volume = value;
    }

    public void a(int ignored, b button) {
    }

    public void a(Context context) {
    }

    public void a(SharedPreferences.Editor editor, String key, TreeMap<String, String> values) {
    }

    public void a(SharedPreferences preferences, b button, int index) {
    }

    public void a(SharedPreferences preferences, String key, TreeMap<String, String> values) {
    }

    public void a(b button, Rect rect, boolean landscape) {
    }

    public void a(c value) {
        shaderMode = value;
    }

    public void a(d value) {
        audioMode = value;
    }

    public void a(boolean value) {
        uiButtonsEnabled = value;
    }

    public c b() {
        return shaderMode;
    }

    public void b(float value) {
        opacity = value;
    }

    public void b(Context context) {
    }

    public void b(boolean value) {
        turboEnabled = value;
    }

    public Iterable<b> c() {
        return Arrays.asList(Settings.b.values());
    }

    public void c(boolean value) {
        filteringEnabled = value;
    }

    public d d() {
        return audioMode;
    }

    public void d(boolean value) {
        fullScreen = value;
    }

    public float e() {
        return opacity;
    }

    public void e(boolean value) {
        audioEnabled = value;
    }

    public boolean f() {
        return uiButtonsEnabled;
    }

    public void f(boolean value) {
        rewindEnabled = value;
    }

    public boolean g() {
        return turboEnabled;
    }

    public void g(boolean value) {
        showFps = value;
    }

    public boolean h() {
        return filteringEnabled;
    }

    public void h(boolean value) {
        debugInput = value;
    }

    public int i(boolean value) {
        return value ? 1 : 0;
    }

    public boolean i() {
        return fullScreen;
    }

    public static float getAudioVolume() { return staticAudioVolume; }
    public static boolean isButtonsVibrationEnabled() { return staticButtonsVibration; }
    public static boolean isVoiceChatEnabled() { return staticVoiceChatEnabled; }
    public static boolean isUIButtonsEnbled() { return staticUIButtonsEnabled; }
    public static Orientation getPreferedOrientation() { return staticOrientation; }
    public static boolean isFullscreenEnabled() { return staticFullscreenEnabled; }
    public static boolean isBtnATurbo() { return staticBtnATurbo; }
    public static boolean isBtnBTurbo() { return staticBtnBTurbo; }
    public static boolean isAdsDisabled() { return staticDisableAds; }
    public static boolean isAutoSearchGamesOnResumeEnabled() { return !staticDisableAutoSearchGamesOnResume; }
    public static DisplayFilterMode getDisplayFilterMode() { return staticFilterMode; }
    public static float getUIButtonsOpacity() { return staticUIButtonsOpacity; }
    public static Iterable<Button> getMappedButton(int keycode) { return staticKey2ButtonMap.get(keycode); }
    public static Iterable<Map.Entry<Button, Integer>> getMappedButtons() { return staticButton2KeyMap.entrySet(); }
    public static Iterable<Map.Entry<Button, Rect>> getUIButtonsRects(boolean portrait) {
        return portrait ? staticPortraitButtonRects.entrySet() : staticLandscapeButtonRects.entrySet();
    }
    public static int getNumAssignedUIButtonsRects(boolean portrait) {
        return portrait ? staticPortraitButtonRects.size() : staticLandscapeButtonRects.size();
    }
    public static Rect getUIButtonRect(Button button, boolean portrait) {
        return portrait ? staticPortraitButtonRects.get(button) : staticLandscapeButtonRects.get(button);
    }
    public static void setAudioVolume(float value) { staticAudioVolume = value; }
    public static void enableButtonsVibration(boolean value) { staticButtonsVibration = value; }
    public static void enableVoiceChat(boolean value) { staticVoiceChatEnabled = value; }
    public static void enableUIButtons(boolean value) { staticUIButtonsEnabled = value; }
    public static void setPreferedOrientation(Orientation value) { staticOrientation = value; }
    public static void enableFullscreen(boolean value) { staticFullscreenEnabled = value; }
    public static void enableBtnATurbo(boolean value) { staticBtnATurbo = value; }
    public static void enableBtnBTurbo(boolean value) { staticBtnBTurbo = value; }
    public static void enableAds(boolean value) { staticDisableAds = !value; }
    public static void enableAutoSearchGamesOnResume(boolean value) { staticDisableAutoSearchGamesOnResume = !value; }
    public static void setDisplayFilterMode(DisplayFilterMode value) { staticFilterMode = value; }
    public static void setUIButtonsOpacity(float value) { staticUIButtonsOpacity = value; }
    public static void setUIButtonRect(Button button, Rect rect, boolean portrait) {
        if (portrait) {
            staticPortraitButtonRects.put(button, rect);
        } else {
            staticLandscapeButtonRects.put(button, rect);
        }
    }
    public static void resetUIButtonsRects(boolean portrait) {
        if (portrait) {
            staticPortraitButtonRects.clear();
        } else {
            staticLandscapeButtonRects.clear();
        }
    }
    public static void mapKeyToButton(int keycode, Button button) {
        staticButton2KeyMap.put(button, keycode);
        HashSet<Button> buttons = staticKey2ButtonMap.get(keycode);
        if (buttons == null) {
            buttons = new HashSet<>();
            staticKey2ButtonMap.put(keycode, buttons);
        }
        buttons.add(button);
    }
    public static void resetMappedButtonSetting() {
        staticKey2ButtonMap.clear();
        staticButton2KeyMap.clear();
    }
    public static void loadGlobalSettings(Context context) {
    }
    public static void saveGlobalSettings(Context context) {
    }

    public boolean j() {
        return audioEnabled;
    }

    public void j(boolean value) {
        uiButtonsEnabled = value;
    }

    public boolean k() {
        return rewindEnabled;
    }

    public boolean l() {
        return showFps;
    }

    public void m() {
    }

    private Rect defaultRect(b button) {
        switch (button.ordinal() % 8) {
            case 0:
                return new Rect(40, 80, 240, 280);
            case 1:
                return new Rect(260, 80, 460, 280);
            case 2:
                return new Rect(40, 300, 240, 500);
            case 3:
                return new Rect(260, 300, 460, 500);
            case 4:
                return new Rect(540, 120, 720, 300);
            case 5:
                return new Rect(740, 120, 920, 300);
            case 6:
                return new Rect(350, 20, 520, 100);
            default:
                return new Rect(560, 20, 730, 100);
        }
    }
}
