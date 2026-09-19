package com.hqgame.networknes;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class GameSurfaceView extends GLSurfaceView {
    private static volatile long nativeHandle;
    private static volatile boolean nativeInitialized;
    private static final ThreadLocal<GameSurfaceView> javaHandle = new ThreadLocal<>();
    private static final LinkedList<Runnable> persistentTasks = new LinkedList<>();

    private int width = -1;
    private int height = -1;
    private final MultinessGameActivity activity;
    private boolean surfaceReady;
    private String pendingGamePath;
    private boolean remoteClientMode;
    private boolean remoteConnected;
    private boolean remoteRetryScheduled;
    private boolean shuttingDown;
    private boolean uiButtonsEnabled = true;
    private float uiButtonsOpacity = 0.65f;
    private boolean turboAEnabled;
    private boolean turboBEnabled;
    private String remoteHost;
    private int remotePort;
    private String remoteClientName;

    public GameSurfaceView(MultinessGameActivity activity) {
        super(activity);
        this.activity = activity;
        NesDiagnostics.event("GameSurfaceView constructor before BaseActivity.init");
        BaseActivity.init(activity);
        NesDiagnostics.event("GameSurfaceView constructor before ensureNativeInitialized");
        ensureNativeInitialized();
        NesDiagnostics.event("GameSurfaceView constructor after ensureNativeInitialized");
        setEGLContextClientVersion(2);
        setRenderer(new Renderer());
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private static synchronized void ensureNativeInitialized() {
        if (!nativeInitialized) {
            NesDiagnostics.event("GameSurfaceView.initNative before");
            initNative();
            NesDiagnostics.event("GameSurfaceView.initNative after");
            nativeInitialized = true;
        }
    }

    public void loadAndStartGame(final String path) {
        NesDiagnostics.event("GameSurfaceView.loadAndStartGame queue path=" + path);
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (!surfaceReady) {
                    NesDiagnostics.event("GameSurfaceView.loadAndStartGame deferred until surface ready");
                    pendingGamePath = path;
                    return;
                }
                loadAndStartGameNow(path);
            }
        });
    }

    private void loadAndStartGameNow(String path) {
        NesDiagnostics.event("GameSurfaceView.loadAndStartGame run before initGame");
        initGameNativeIfNeeded();
        NesDiagnostics.event("GameSurfaceView.verifyGameNative before");
        boolean verified = verifyGameNative(nativeHandle, path);
        NesDiagnostics.event("GameSurfaceView.verifyGameNative after result=" + verified);
        if (!verified) {
            fatalError("Unsupported NES file");
            return;
        }
        NesDiagnostics.event("GameSurfaceView.loadAndStartGameNative before");
        loadAndStartGameNative(nativeHandle, path);
        NesDiagnostics.event("GameSurfaceView.loadAndStartGameNative after");
    }

    public void enableRemoteController(final int port, final String hostName) {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                initGameNativeIfNeeded();
                String bindAddress = hostName == null || hostName.trim().isEmpty() ? null : hostName;
                NesDiagnostics.event("GameSurfaceView.enableRemoteControllerNative before port=" + port + " hostName=" + hostName + " bind=" + bindAddress);
                boolean enabled = enableRemoteControllerNative(nativeHandle, port, hostName, bindAddress);
                NesDiagnostics.event("GameSurfaceView.enableRemoteControllerNative after result=" + enabled);
                if (!enabled) {
                    fatalError("Failed to start game server");
                }
            }
        });
    }

    public void loadRemote(final String ip, final int port, final String clientName) {
        remoteClientMode = true;
        remoteConnected = false;
        remoteRetryScheduled = false;
        remoteHost = ip;
        remotePort = port;
        remoteClientName = clientName;
        queueEvent(new Runnable() {
            @Override
            public void run() {
                loadRemoteOnGlThread(ip, port, clientName);
            }
        });
    }

    private void loadRemoteOnGlThread(String ip, int port, String clientName) {
        if (shuttingDown) {
            return;
        }
        initGameNativeIfNeeded();
        NesDiagnostics.event("GameSurfaceView.loadRemoteNative before host=" + ip + " port=" + port);
        disableRemoteControllerNative(nativeHandle);
        shutdownGameNative(nativeHandle);
        if (!loadRemoteNative(nativeHandle, ip, port, clientName)) {
            NesDiagnostics.event("GameSurfaceView.loadRemoteNative immediate failure");
            scheduleRemoteRetry();
        } else {
            NesDiagnostics.event("GameSurfaceView.loadRemoteNative after");
            scheduleRemoteRetry();
        }
    }

    private void scheduleRemoteRetry() {
        if (!remoteClientMode || remoteConnected || remoteRetryScheduled || shuttingDown || remoteHost == null) {
            return;
        }
        remoteRetryScheduled = true;
        NesDiagnostics.event("GameSurfaceView.scheduleRemoteRetry");
        postDelayed(new Runnable() {
            @Override
            public void run() {
                remoteRetryScheduled = false;
                if (!remoteClientMode || remoteConnected || shuttingDown || remoteHost == null) {
                    return;
                }
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        loadRemoteOnGlThread(remoteHost, remotePort, remoteClientName);
                    }
                });
            }
        }, 3000);
    }

    public void shutdownGame() {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                shutdownGameOnGlThread();
            }
        });
    }

    public void shutdownGameAndClose() {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                shutdownGameOnGlThread();
                post(new Runnable() {
                    @Override
                    public void run() {
                        activity.finish();
                    }
                });
            }
        });
    }

    public void resetRunningGame() {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (nativeHandle == 0 || shuttingDown) {
                    return;
                }
                NesDiagnostics.event("GameSurfaceView.resetRunningGame");
                resetGameNative(nativeHandle);
            }
        });
    }

    public boolean toggleUiButtons() {
        uiButtonsEnabled = !uiButtonsEnabled;
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (nativeHandle == 0 || shuttingDown) {
                    return;
                }
                NesDiagnostics.event("GameSurfaceView.toggleUiButtons enabled=" + uiButtonsEnabled);
                enableUIButtonsNative(nativeHandle, uiButtonsEnabled);
            }
        });
        return uiButtonsEnabled;
    }

    public float cycleUiButtonsOpacity() {
        if (uiButtonsOpacity < 0.5f) {
            uiButtonsOpacity = 0.65f;
        } else if (uiButtonsOpacity < 0.9f) {
            uiButtonsOpacity = 1.0f;
        } else {
            uiButtonsOpacity = 0.35f;
        }
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (nativeHandle == 0 || shuttingDown) {
                    return;
                }
                NesDiagnostics.event("GameSurfaceView.cycleUiButtonsOpacity value=" + uiButtonsOpacity);
                setUIButtonsOpacityNative(nativeHandle, uiButtonsOpacity);
            }
        });
        return uiButtonsOpacity;
    }

    public boolean toggleTurboA() {
        turboAEnabled = !turboAEnabled;
        applyTurboSettings();
        return turboAEnabled;
    }

    public boolean toggleTurboB() {
        turboBEnabled = !turboBEnabled;
        applyTurboSettings();
        return turboBEnabled;
    }

    public void loadState(final String file) {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (nativeHandle == 0 || shuttingDown) {
                    return;
                }
                NesDiagnostics.event("GameSurfaceView.loadState file=" + file);
                int result = loadStateNative(nativeHandle, file);
                if (result == -7) {
                    showToast("\u042D\u0442\u043E \u0441\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u0438\u0435 \u043E\u0442 \u0434\u0440\u0443\u0433\u043E\u0439 \u0438\u0433\u0440\u044B", Toast.LENGTH_LONG);
                } else if (result >= 0) {
                    showToast("\u0421\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u0438\u0435 \u0437\u0430\u0433\u0440\u0443\u0436\u0435\u043D\u043E", Toast.LENGTH_SHORT);
                } else {
                    showToast("\u041D\u0435 \u0443\u0434\u0430\u043B\u043E\u0441\u044C \u0437\u0430\u0433\u0440\u0443\u0437\u0438\u0442\u044C: " + result, Toast.LENGTH_LONG);
                }
            }
        });
    }

    public void saveState(final String file) {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (nativeHandle == 0 || shuttingDown) {
                    return;
                }
                NesDiagnostics.event("GameSurfaceView.saveState file=" + file);
                int result = saveStateNative(nativeHandle, file);
                if (result >= 0) {
                    showToast("\u0418\u0433\u0440\u0430 \u0441\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u0430", Toast.LENGTH_SHORT);
                } else {
                    showToast("\u041D\u0435 \u0443\u0434\u0430\u043B\u043E\u0441\u044C \u0441\u043E\u0445\u0440\u0430\u043D\u0438\u0442\u044C: " + result, Toast.LENGTH_LONG);
                }
            }
        });
    }

    private void applyTurboSettings() {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (nativeHandle == 0 || shuttingDown) {
                    return;
                }
                NesDiagnostics.event("GameSurfaceView.applyTurboSettings a=" + turboAEnabled + " b=" + turboBEnabled);
                switchABTurboModeNative(nativeHandle, turboAEnabled, turboBEnabled);
            }
        });
    }

    private void shutdownGameOnGlThread() {
        if (nativeHandle == 0) {
            NesDiagnostics.event("GameSurfaceView.shutdownGameOnGlThread skipped no handle");
            return;
        }
        shuttingDown = true;
        remoteClientMode = false;
        long handle = nativeHandle;
        NesDiagnostics.event("GameSurfaceView.shutdownGameOnGlThread before handle=" + handle);
        try {
            disableRemoteControllerNative(handle);
            shutdownGameNative(handle);
        } finally {
            nativeHandle = 0;
            surfaceReady = false;
            pendingGamePath = null;
            NesDiagnostics.event("GameSurfaceView.shutdownGameOnGlThread after");
        }
    }

    @Override
    public void onPause() {
        NesDiagnostics.event("GameSurfaceView.onPause skip native lifecycle");
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (!surfaceReady || nativeHandle == 0) {
                    NesDiagnostics.event("GameSurfaceView.onResume deferred until surface ready");
                    return;
                }
                resumeRuntimeAfterSurfaceReady();
            }
        });
    }

    @Override
    public void queueEvent(final Runnable task) {
        super.queueEvent(new Runnable() {
            @Override
            public void run() {
                javaHandle.set(GameSurfaceView.this);
                try {
                    task.run();
                } finally {
                    javaHandle.set(null);
                }
            }
        });
    }

    @Override
    public boolean onTouchEvent(final MotionEvent sourceEvent) {
        final MotionEvent event = MotionEvent.obtainNoHistory(sourceEvent);
        queueEvent(new Runnable() {
            @Override
            public void run() {
                initGameNativeIfNeeded();
                int action = event.getActionMasked();
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                float x = event.getX(index);
                float y = height - event.getY(index);

                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        onTouchBeganNative(nativeHandle, id, x, y);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        for (int p = 0; p < event.getPointerCount(); p++) {
                            onTouchMovedNative(nativeHandle, event.getPointerId(p), event.getX(p), height - event.getY(p));
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_CANCEL:
                        onTouchEndedNative(nativeHandle, id, x, y);
                        break;
                    default:
                        break;
                }

                event.recycle();
            }
        });
        return true;
    }

    private void initGameNativeIfNeeded() {
        if (nativeHandle != 0) {
            return;
        }
        NesDiagnostics.event("GameSurfaceView.initGameNative before");
        nativeHandle = initGameNative(loadAssetBytes(getContext(), "NesDatabase.xml"));
        NesDiagnostics.event("GameSurfaceView.initGameNative after handle=" + nativeHandle);
    }

    private void resumeRuntimeAfterSurfaceReady() {
        applyRuntimeSettings();
        NesDiagnostics.event("GameSurfaceView.onResumeNative before");
        onResumeNative(nativeHandle);
        NesDiagnostics.event("GameSurfaceView.onResumeNative after");
    }

    private void applyRuntimeSettings() {
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings before");
        // The embedded skyTox launcher creates the native game object directly on
        // the GL thread. Multiness' activity lifecycle hook crashes in this mode,
        // so only the runtime options that are safe after resetGameViewNative are applied.
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings audio volume");
        setAudioVolumeNative(nativeHandle, 1.0f);
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings audio permission");
        setAudioRecordPermissionChangedNative(nativeHandle, false);
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings audio input skipped");
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings buttons enabled");
        enableUIButtonsNative(nativeHandle, uiButtonsEnabled);
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings buttons opacity");
        setUIButtonsOpacityNative(nativeHandle, uiButtonsOpacity);
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings fullscreen");
        enableFullScreenNative(nativeHandle, false);
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings turbo");
        switchABTurboModeNative(nativeHandle, turboAEnabled, turboBEnabled);
        NesDiagnostics.event("GameSurfaceView.applyRuntimeSettings after");
    }

    private static byte[] loadAssetBytes(Context context, String name) {
        try {
            InputStream inputStream = context.getAssets().open(name);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            inputStream.close();
            return output.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private final class Renderer implements GLSurfaceView.Renderer {
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            javaHandle.set(GameSurfaceView.this);
            try {
                NesDiagnostics.event("Renderer.onSurfaceCreated");
                initGameNativeIfNeeded();
            } finally {
                javaHandle.set(null);
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            javaHandle.set(GameSurfaceView.this);
            try {
                NesDiagnostics.event("Renderer.onSurfaceChanged width=" + width + " height=" + height);
                GameSurfaceView.this.width = width;
                GameSurfaceView.this.height = height;
                NesDiagnostics.event("resetGameViewNative before");
                resetGameViewNative(getContext().getAssets(), nativeHandle, 2, width, height, true);
                NesDiagnostics.event("resetGameViewNative after");
                surfaceReady = true;
                resumeRuntimeAfterSurfaceReady();
                if (pendingGamePath != null) {
                    String path = pendingGamePath;
                    pendingGamePath = null;
                    NesDiagnostics.event("Renderer starting deferred game");
                    loadAndStartGameNow(path);
                }
            } finally {
                javaHandle.set(null);
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            javaHandle.set(GameSurfaceView.this);
            try {
                long handle = nativeHandle;
                if (!surfaceReady || handle == 0 || width <= 0 || height <= 0) {
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    return;
                }
                synchronized (persistentTasks) {
                    while (!persistentTasks.isEmpty()) {
                        persistentTasks.removeFirst().run();
                    }
                }
                GLES20.glViewport(0, 0, width, height);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                renderGameViewNative(handle, false, 0f);
            } finally {
                javaHandle.set(null);
            }
        }
    }

    public static void runOnGameThread(final GLSurfaceView view, final long nativeFunctionId, final long nativeFunctionArg) {
        NesDiagnostics.event("GameSurfaceView.runOnGameThread request fn=" + nativeFunctionId + " arg=" + nativeFunctionArg + " viewNull=" + (view == null));
        view.queueEvent(new Runnable() {
            @Override
            public void run() {
                GameSurfaceView handle = javaHandle.get();
                if (handle != null) {
                    NesDiagnostics.event("GameSurfaceView.runOnGameThread invoke before");
                    invokeNativeFunction(nativeHandle, nativeFunctionId, nativeFunctionArg);
                    NesDiagnostics.event("GameSurfaceView.runOnGameThread invoke after");
                } else {
                    NesDiagnostics.event("GameSurfaceView.runOnGameThread skipped no javaHandle");
                }
            }
        });
    }

    public void finishGamePage() {
        activity.finish();
    }

    public BaseActivity getActivity() {
        return activity;
    }

    public void showToast(final String message, final int duration) {
        post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getContext(), message, duration).show();
            }
        });
    }

    public void showToast(final int messageId, final int duration) {
        post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getContext(), messageId, duration).show();
            }
        });
    }

    public void showProgressDialog(int messageId, Runnable onCancel) {
        // skyTox embeds the emulator without the original app's blocking progress dialogs.
    }

    public static void fatalError(final String message) {
        NesDiagnostics.event("GameSurfaceView.fatalError message=" + message);
        final GameSurfaceView handle = javaHandle.get();
        if (handle != null) {
            handle.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(handle.getContext(), message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    public static void machineEventCallback(int event, int value) {
        NesDiagnostics.event("GameSurfaceView.machineEventCallback int event=" + event + " value=" + value);
        if (event == 0 && value < 0) {
            fatalError("NES load failed: " + value);
        } else if (event == 0 && value >= 0) {
            NesDiagnostics.event("GameSurfaceView.machineEventCallback load success");
        } else if (event == 1) {
            NesDiagnostics.event("GameSurfaceView.machineEventCallback load remote result=" + value);
            GameSurfaceView handle = javaHandle.get();
            if (handle != null && value < 0) {
                handle.scheduleRemoteRetry();
            }
        } else if (event == 5) {
            NesDiagnostics.event("GameSurfaceView.machineEventCallback remote connected");
            GameSurfaceView handle = javaHandle.get();
            if (handle != null) {
                handle.remoteConnected = true;
                handle.remoteRetryScheduled = false;
            }
        } else if (event == 7) {
            NesDiagnostics.event("GameSurfaceView.machineEventCallback client connected");
        } else if (event == 8) {
            NesDiagnostics.event("GameSurfaceView.machineEventCallback client disconnected");
        } else if (event == 6 || event == 15) {
            NesDiagnostics.event("GameSurfaceView.machineEventCallback remote disconnected/poweroff");
            GameSurfaceView handle = javaHandle.get();
            if (handle != null && handle.remoteClientMode && !handle.shuttingDown) {
                handle.remoteConnected = false;
                handle.scheduleRemoteRetry();
            }
        }
    }

    public static void machineEventCallback(int event, float value) {
        NesDiagnostics.event("GameSurfaceView.machineEventCallback float event=" + event + " value=" + value);
    }

    public static void machineEventCallback(int event, long value) {
        NesDiagnostics.event("GameSurfaceView.machineEventCallback long event=" + event + " value=" + value);
    }

    public static void machineEventCallback(int event, byte[] value) {
        NesDiagnostics.event("GameSurfaceView.machineEventCallback bytes event=" + event + " length=" + (value == null ? -1 : value.length));
        GameSurfaceView handle = javaHandle.get();
        if (event == 5) {
            NesDiagnostics.event("GameSurfaceView.machineEventCallback bytes remote connected");
            if (handle != null) {
                handle.remoteConnected = true;
                handle.remoteRetryScheduled = false;
            }
        } else if (event == 6 || event == 8 || event == 15) {
            NesDiagnostics.event("GameSurfaceView.machineEventCallback bytes remote disconnected");
            if (handle != null && handle.remoteClientMode && !handle.shuttingDown) {
                handle.remoteConnected = false;
                handle.scheduleRemoteRetry();
            }
        }
    }

    public static void displayInviteDialog(String inviteData, int context) {
    }

    public static void clientAboutToConnectToRemote(String inviteId, String guid, int context) {
    }

    public static void clientConnectivityTestCallback(String inviteId, String guid, Object callback, boolean result) {
    }

    public static String createPublicServerMetaData(String publicName, String inviteData, int context) {
        return "";
    }

    private static native void initNative();
    private static native long initGameNative(byte[] databaseData);
    private static native boolean verifyGameNative(long nativeHandle, String path);
    private native boolean loadAndStartGameNative(long nativeHandle, String path);
    private native boolean loadRemoteNative(long nativeHandle, String ip, int port, String clientName);
    private native void resetGameNative(long nativeHandle);
    private native void shutdownGameNative(long nativeHandle);
    private native boolean enableRemoteControllerNative(long nativeHandle, int port, String hostName, String hostIpBound);
    private native void disableRemoteControllerNative(long nativeHandle);
    private native int loadStateNative(long nativeHandle, String file);
    private native int saveStateNative(long nativeHandle, String file);
    private native void setAudioVolumeNative(long nativeHandle, float gain);
    private native void setAudioRecordPermissionChangedNative(long nativeHandle, boolean hasPermission);
    private native void enableAudioInput(long nativeHandle, boolean enable);
    private native void enableUIButtonsNative(long nativeHandle, boolean enable);
    private native void setUIButtonsOpacityNative(long nativeHandle, float opacity);
    private native void enableFullScreenNative(long nativeHandle, boolean enable);
    private native void switchABTurboModeNative(long nativeHandle, boolean enableATurbo, boolean enableBTurbo);
    private native void resetGameViewNative(AssetManager assetManager, long nativeHandle, int esVersionMajor, int width, int height, boolean contextRecreate);
    private native void renderGameViewNative(long nativeHandle, boolean drawButtonsOnly, float buttonBoundingBoxOutlineSize);
    private native void onResumeNative(long nativeHandle);
    private native void onPauseOnUIThreadNative(long nativeHandle);
    private static native void invokeNativeFunction(long nativeHandle, long nativeFunctionId, long nativeFunctionArg);
    private native boolean onTouchBeganNative(long nativeHandle, int id, float x, float y);
    private native boolean onTouchMovedNative(long nativeHandle, int id, float x, float y);
    private native boolean onTouchEndedNative(long nativeHandle, int id, float x, float y);
}
