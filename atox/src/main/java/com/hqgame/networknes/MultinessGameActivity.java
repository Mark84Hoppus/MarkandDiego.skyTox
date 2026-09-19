package com.hqgame.networknes;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FilenameFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class MultinessGameActivity extends BaseActivity {
    public static final String EXTRA_ROM_PATH = "romPath";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String MODE_SINGLE = "single";
    public static final String MODE_HOST = "host";
    public static final String MODE_CLIENT = "client";
    public static final int DEFAULT_PORT = 61112;
    private static final int MENU_RESET = 1;
    private static final int MENU_TOGGLE_BUTTONS = 2;
    private static final int MENU_OPACITY = 3;
    private static final int MENU_TURBO_A = 4;
    private static final int MENU_TURBO_B = 5;
    private static final int MENU_QUICK_SAVE = 6;
    private static final int MENU_QUICK_LOAD = 7;
    private static final int MAX_QUICK_SAVES_PER_GAME = 5;
    private static final String QUICK_SAVE_PREFIX = "quicksave";

    private GameSurfaceView gameView;
    private String mode;
    private String currentGameName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NesDiagnostics.event("MultinessGameActivity.onCreate start");
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        NesDiagnostics.event("creating GameSurfaceView");
        gameView = new GameSurfaceView(this);
        NesDiagnostics.event("GameSurfaceView created");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff000000);
        FrameLayout topBar = new FrameLayout(this);
        topBar.setBackgroundColor(0xff000000);
        root.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)));
        root.addView(gameView, new LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        TextView exit = new TextView(this);
        exit.setText("\u0412\u042B\u0425\u041E\u0414");
        exit.setTextColor(0xffffffff);
        exit.setTextSize(18f);
        exit.setGravity(Gravity.CENTER);
        exit.setPadding(28, 18, 28, 18);
        FrameLayout.LayoutParams exitParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.TOP);
        exitParams.leftMargin = dp(8);
        topBar.addView(exit, exitParams);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (gameView != null) {
                    gameView.shutdownGameAndClose();
                } else {
                    finish();
                }
            }
        });

        TextView menu = new TextView(this);
        menu.setText("\u22EE");
        menu.setTextColor(0xffffffff);
        menu.setTextSize(30f);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(28, 2, 28, 2);
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.TOP);
        menuParams.rightMargin = dp(4);
        topBar.addView(menu, menuParams);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGameMenu(v);
            }
        });

        setContentView(root);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) {
            mode = MODE_SINGLE;
        }

        String romPath = getIntent().getStringExtra(EXTRA_ROM_PATH);
        if (romPath != null) {
            currentGameName = new File(romPath).getName();
        }
        NesDiagnostics.event("mode=" + mode + " rom=" + romPath);
        if (MODE_CLIENT.equals(mode)) {
            BaseActivity.bindProcessToWifiNetwork(this);
            String host = getIntent().getStringExtra(EXTRA_HOST);
            int port = getIntent().getIntExtra(EXTRA_PORT, DEFAULT_PORT);
            NesDiagnostics.event("loadRemote host=" + host + " port=" + port);
            gameView.loadRemote(host, port, BaseActivity.getCurrentHostIPAddress());
        } else {
            if (MODE_HOST.equals(mode)) {
                BaseActivity.bindProcessToWifiNetwork(this);
                NesDiagnostics.event("enableRemoteController");
                gameView.enableRemoteController(DEFAULT_PORT, BaseActivity.getCurrentHostIPAddress());
            }
            if (romPath != null) {
                String nativeRomPath = prepareRomForNative(romPath);
                NesDiagnostics.event("loadAndStartGame nativePath=" + nativeRomPath);
                gameView.loadAndStartGame(nativeRomPath);
            }
        }
        NesDiagnostics.event("MultinessGameActivity.onCreate done");
    }

    private void showGameMenu(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        popupMenu.getMenu().add(0, MENU_RESET, 0, "\u0421\u0431\u0440\u043E\u0441 \u0438\u0433\u0440\u044B");
        popupMenu.getMenu().add(0, MENU_TOGGLE_BUTTONS, 1, "\u041F\u043E\u043A\u0430\u0437\u0430\u0442\u044C/\u0441\u043A\u0440\u044B\u0442\u044C \u043A\u043D\u043E\u043F\u043A\u0438");
        popupMenu.getMenu().add(0, MENU_OPACITY, 2, "\u041F\u0440\u043E\u0437\u0440\u0430\u0447\u043D\u043E\u0441\u0442\u044C \u043A\u043D\u043E\u043F\u043E\u043A");
        popupMenu.getMenu().add(0, MENU_TURBO_A, 3, "\u0422\u0443\u0440\u0431\u043E A");
        popupMenu.getMenu().add(0, MENU_TURBO_B, 4, "\u0422\u0443\u0440\u0431\u043E B");
        popupMenu.getMenu().add(0, MENU_QUICK_SAVE, 5, "\u0411\u044B\u0441\u0442\u0440\u043E\u0435 \u0441\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u0438\u0435");
        popupMenu.getMenu().add(0, MENU_QUICK_LOAD, 6, "\u0411\u044B\u0441\u0442\u0440\u0430\u044F \u0437\u0430\u0433\u0440\u0443\u0437\u043A\u0430");
        popupMenu.setOnMenuItemClickListener(item -> {
            if (gameView == null) {
                return true;
            }
            switch (item.getItemId()) {
                case MENU_RESET:
                    gameView.resetRunningGame();
                    Toast.makeText(this, "\u0418\u0433\u0440\u0430 \u0441\u0431\u0440\u043E\u0448\u0435\u043D\u0430", Toast.LENGTH_SHORT).show();
                    return true;
                case MENU_TOGGLE_BUTTONS:
                    boolean buttonsEnabled = gameView.toggleUiButtons();
                    Toast.makeText(this, buttonsEnabled ? "\u041A\u043D\u043E\u043F\u043A\u0438 \u0432\u043A\u043B\u044E\u0447\u0435\u043D\u044B" : "\u041A\u043D\u043E\u043F\u043A\u0438 \u0441\u043A\u0440\u044B\u0442\u044B", Toast.LENGTH_SHORT).show();
                    return true;
                case MENU_OPACITY:
                    float opacity = gameView.cycleUiButtonsOpacity();
                    Toast.makeText(this, "\u041F\u0440\u043E\u0437\u0440\u0430\u0447\u043D\u043E\u0441\u0442\u044C: " + Math.round(opacity * 100f) + "%", Toast.LENGTH_SHORT).show();
                    return true;
                case MENU_TURBO_A:
                    boolean turboA = gameView.toggleTurboA();
                    Toast.makeText(this, turboA ? "\u0422\u0443\u0440\u0431\u043E A \u0432\u043A\u043B\u044E\u0447\u0435\u043D\u043E" : "\u0422\u0443\u0440\u0431\u043E A \u0432\u044B\u043A\u043B\u044E\u0447\u0435\u043D\u043E", Toast.LENGTH_SHORT).show();
                    return true;
                case MENU_TURBO_B:
                    boolean turboB = gameView.toggleTurboB();
                    Toast.makeText(this, turboB ? "\u0422\u0443\u0440\u0431\u043E B \u0432\u043A\u043B\u044E\u0447\u0435\u043D\u043E" : "\u0422\u0443\u0440\u0431\u043E B \u0432\u044B\u043A\u043B\u044E\u0447\u0435\u043D\u043E", Toast.LENGTH_SHORT).show();
                    return true;
                case MENU_QUICK_SAVE:
                    performQuickSave();
                    return true;
                case MENU_QUICK_LOAD:
                    performQuickLoad();
                    return true;
                default:
                    return false;
            }
        });
        popupMenu.show();
    }

    private void performQuickSave() {
        if (!canUseLocalState()) {
            return;
        }
        File[] savedFiles = listQuickSavedFiles();
        int slot = 0;
        if (savedFiles != null && savedFiles.length > 0) {
            int latestSlot = parseQuickSaveSlot(savedFiles[0].getName());
            if (latestSlot >= 0) {
                slot = (latestSlot + 1) % MAX_QUICK_SAVES_PER_GAME;
            }
        }
        gameView.saveState(constructQuickSavePath(slot));
    }

    private void performQuickLoad() {
        if (!canUseLocalState()) {
            return;
        }
        File[] savedFiles = listQuickSavedFiles();
        if (savedFiles == null || savedFiles.length == 0) {
            Toast.makeText(this, "\u041F\u043E\u043A\u0430 \u043D\u0435\u0442 \u0441\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u0438\u0439", Toast.LENGTH_SHORT).show();
            return;
        }
        gameView.loadState(savedFiles[0].getAbsolutePath());
    }

    private boolean canUseLocalState() {
        if (MODE_CLIENT.equals(mode)) {
            Toast.makeText(this, "\u0421\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u0438\u044F \u0434\u043E\u0441\u0442\u0443\u043F\u043D\u044B \u043D\u0430 \u0442\u0435\u043B\u0435\u0444\u043E\u043D\u0435-\u0441\u0435\u0440\u0432\u0435\u0440\u0435", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (currentGameName == null || currentGameName.length() == 0) {
            Toast.makeText(this, "\u0418\u0433\u0440\u0430 \u0435\u0449\u0435 \u043D\u0435 \u0437\u0430\u043F\u0443\u0449\u0435\u043D\u0430", Toast.LENGTH_SHORT).show();
            return false;
        }
        File dir = getSaveStateDir();
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "\u041D\u0435 \u0443\u0434\u0430\u043B\u043E\u0441\u044C \u0441\u043E\u0437\u0434\u0430\u0442\u044C \u043F\u0430\u043F\u043A\u0443 \u0441\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u0438\u0439", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private File[] listQuickSavedFiles() {
        File[] files = getSaveStateDir().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return parseQuickSaveSlot(name) >= 0;
            }
        });
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    return Long.compare(right.lastModified(), left.lastModified());
                }
            });
        }
        return files;
    }

    private String constructQuickSavePath(int slot) {
        return new File(getSaveStateDir(), QUICK_SAVE_PREFIX + slot + "." + stateGameKey() + ".ns").getAbsolutePath();
    }

    private int parseQuickSaveSlot(String fileName) {
        String prefix = QUICK_SAVE_PREFIX;
        String suffix = "." + stateGameKey() + ".ns";
        if (!fileName.startsWith(prefix) || !fileName.endsWith(suffix)) {
            return -1;
        }
        try {
            int slot = Integer.parseInt(fileName.substring(prefix.length(), fileName.length() - suffix.length()));
            return slot >= 0 && slot < MAX_QUICK_SAVES_PER_GAME ? slot : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private File getSaveStateDir() {
        return new File(getFilesDir(), "nes_states");
    }

    private String stateGameKey() {
        String name = currentGameName == null ? "unknown" : currentGameName;
        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return String.format(Locale.US, "%08x.%s", name.hashCode(), safeName);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String prepareRomForNative(String romPath) {
        File targetDir = new File(getFilesDir(), "nes_roms");
        File targetFile = new File(targetDir, "current.nes");
        try {
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                NesDiagnostics.event("prepareRomForNative mkdir failed dir=" + targetDir.getAbsolutePath());
                return romPath;
            }
            NesDiagnostics.event("prepareRomForNative copy from=" + romPath);
            InputStream input = new FileInputStream(romPath);
            OutputStream output = new FileOutputStream(targetFile, false);
            byte[] buffer = new byte[64 * 1024];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                total += read;
            }
            output.flush();
            output.close();
            input.close();
            NesDiagnostics.event("prepareRomForNative copied bytes=" + total + " to=" + targetFile.getAbsolutePath());
            return targetFile.getAbsolutePath();
        } catch (Exception e) {
            NesDiagnostics.error("prepareRomForNative failed path=" + romPath, e);
            return romPath;
        }
    }

    @Override
    protected void onPause() {
        if (gameView != null) {
            gameView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameView != null) {
            gameView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        NesDiagnostics.event("MultinessGameActivity.onDestroy");
        if (gameView != null) {
            gameView.shutdownGame();
        }
        BaseActivity.clearProcessNetworkBinding(this);
        super.onDestroy();
    }
}
