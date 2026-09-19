package com.hqgame.networknes;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;

public class BaseActivity extends Activity {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static BaseActivity instance;
    private static Context appContext;
    private boolean nativeCreated;
    private boolean lifecycleCreated;

    static {
        loadLibrary("c++_shared");
        loadLibrary("RemoteController");
        loadLibrary("nes");
    }

    private static void loadLibrary(String name) {
        NesDiagnostics.event("BaseActivity.loadLibrary before " + name);
        try {
            System.loadLibrary(name);
            NesDiagnostics.event("BaseActivity.loadLibrary after " + name);
        } catch (Throwable throwable) {
            NesDiagnostics.error("BaseActivity.loadLibrary failed " + name, throwable);
            throw throwable;
        }
    }

    public static synchronized void init(Context context) {
        NesDiagnostics.event("BaseActivity.init start");
        appContext = context.getApplicationContext();
        if (instance == null) {
            if (context instanceof BaseActivity) {
                instance = (BaseActivity) context;
            } else {
                instance = new BaseActivity();
            }
        }
        NesDiagnostics.event("BaseActivity.init before ensureNativeCreated");
        instance.ensureNativeCreated();
        NesDiagnostics.event("BaseActivity.init done");
    }

    private synchronized void ensureNativeCreated() {
        if (!nativeCreated) {
            NesDiagnostics.event("BaseActivity.cacheJVMNative before");
            try {
                cacheJVMNative();
                NesDiagnostics.event("BaseActivity.cacheJVMNative after");
            } catch (Throwable throwable) {
                NesDiagnostics.error("BaseActivity.cacheJVMNative failed", throwable);
                throw throwable;
            }
            // The original app calls more native Activity lifecycle hooks, but the embedded
            // skyTox launcher only needs JVM caching before GameSurfaceView initializes.
            nativeCreated = true;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appContext = getApplicationContext();
        instance = this;
        Settings.loadGlobalSettings(this);
        ensureNativeCreated();
        if (!lifecycleCreated) {
            NesDiagnostics.event("BaseActivity.onCreatedNative before");
            onCreatedNative(savedInstanceState);
            NesDiagnostics.event("BaseActivity.onCreatedNative after");
            lifecycleCreated = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        NesDiagnostics.event("BaseActivity.onStartedNative before");
        onStartedNative();
        NesDiagnostics.event("BaseActivity.onStartedNative after");
    }

    @Override
    protected void onResume() {
        super.onResume();
        NesDiagnostics.event("BaseActivity.onResumedNative before");
        onResumedNative();
        NesDiagnostics.event("BaseActivity.onResumedNative after");
    }

    @Override
    protected void onPause() {
        NesDiagnostics.event("BaseActivity.onPausedNative before");
        onPausedNative();
        NesDiagnostics.event("BaseActivity.onPausedNative after");
        super.onPause();
    }

    @Override
    protected void onStop() {
        NesDiagnostics.event("BaseActivity.onStoppedNative before");
        onStoppedNative();
        NesDiagnostics.event("BaseActivity.onStoppedNative after");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        NesDiagnostics.event("BaseActivity.onDestroyedNative before");
        onDestroyedNative();
        NesDiagnostics.event("BaseActivity.onDestroyedNative after");
        super.onDestroy();
    }

    public static BaseActivity getInstance() {
        return instance;
    }

    private static Class<?> getSettingsClass() {
        NesDiagnostics.event("BaseActivity.getSettingsClass");
        return Settings.class;
    }

    private static void runOnMainThread(final long nativeFunctionId, final long nativeFunctionArg) {
        NesDiagnostics.event("BaseActivity.runOnMainThread request fn=" + nativeFunctionId + " arg=" + nativeFunctionArg);
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                NesDiagnostics.event("BaseActivity.runOnMainThread invoke before");
                invokeNativeFunction(nativeFunctionId, nativeFunctionArg);
                NesDiagnostics.event("BaseActivity.runOnMainThread invoke after");
            }
        });
    }

    private static void onLanServerDiscovered(long requestId, String address, int port, String description) {
        // The skyTox UI uses explicit QR/IP connection for now.
    }

    public static String currentHostIPAddress() {
        return instance != null ? instance.getHostIPAddress() : getHostIPAddress(appContext);
    }

    public static String getCurrentHostIPAddress() {
        return getHostIPAddress(appContext);
    }

    public static boolean bindProcessToWifiNetwork(Context context) {
        Network wifiNetwork = findWifiNetwork(context);
        if (wifiNetwork == null) {
            NesDiagnostics.event("BaseActivity.bindProcessToWifiNetwork no wifi network");
            return false;
        }
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean bound = connectivityManager != null && connectivityManager.bindProcessToNetwork(wifiNetwork);
            NesDiagnostics.event("BaseActivity.bindProcessToWifiNetwork result=" + bound);
            return bound;
        } catch (Throwable throwable) {
            NesDiagnostics.error("BaseActivity.bindProcessToWifiNetwork failed", throwable);
            return false;
        }
    }

    public static void clearProcessNetworkBinding(Context context) {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                connectivityManager.bindProcessToNetwork(null);
                NesDiagnostics.event("BaseActivity.clearProcessNetworkBinding done");
            }
        } catch (Throwable throwable) {
            NesDiagnostics.error("BaseActivity.clearProcessNetworkBinding failed", throwable);
        }
    }

    public static Socket createWifiSocket(Context context, String host, int port, int timeoutMs) throws IOException {
        Network wifiNetwork = findWifiNetwork(context);
        Socket socket = new Socket();
        if (wifiNetwork != null) {
            NesDiagnostics.event("BaseActivity.createWifiSocket bind socket to wifi host=" + host + " port=" + port);
            wifiNetwork.bindSocket(socket);
        } else {
            NesDiagnostics.event("BaseActivity.createWifiSocket no wifi network host=" + host + " port=" + port);
        }
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        return socket;
    }

    private static String getHostIPAddress() {
        return getHostIPAddress(appContext);
    }

    private static String getHostIPAddress(Context context) {
        if (context != null) {
            try {
                WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    String wifiIp = Formatter.formatIpAddress(wifiManager.getConnectionInfo().getIpAddress());
                    if (wifiIp != null && !"0.0.0.0".equals(wifiIp)) {
                        return wifiIp;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = networkInterface.getName();
                if (name == null || !(name.startsWith("wlan") || name.startsWith("ap"))) {
                    continue;
                }
                for (java.net.InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                return Formatter.formatIpAddress(wifiManager.getConnectionInfo().getIpAddress());
            }
        } catch (Exception ignored) {
        }

        return "0.0.0.0";
    }

    private static Network findWifiNetwork(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                return null;
            }
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    NesDiagnostics.event("BaseActivity.findWifiNetwork found wifi");
                    return network;
                }
            }
        } catch (Throwable throwable) {
            NesDiagnostics.error("BaseActivity.findWifiNetwork failed", throwable);
        }
        return null;
    }

    private static Object getAssetsManagerObject() {
        return appContext != null ? appContext.getAssets() : null;
    }

    @Override
    public void finish() {
        super.finish();
    }

    private native void cacheJVMNative();
    private native void onCreatedNative(Bundle savedInstanceState);
    private native void onDestroyedNative();
    private native void onResumedNative();
    private native void onPausedNative();
    private native void onStartedNative();
    private native void onStoppedNative();
    private static native void invokeNativeFunction(long nativeFunctionId, long nativeFunctionArg);
}
