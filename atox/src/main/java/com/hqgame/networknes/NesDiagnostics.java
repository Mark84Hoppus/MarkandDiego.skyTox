package com.hqgame.networknes;

import android.os.Environment;
import android.os.Process;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class NesDiagnostics {
    private NesDiagnostics() {
    }

    public static void event(String message) {
        write("EVENT " + message, null);
    }

    public static void error(String message, Throwable throwable) {
        write("ERROR " + message, throwable);
    }

    private static synchronized void write(String message, Throwable throwable) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "skyTox files/skyTox logs");
            dir.mkdirs();
            File file = new File(dir, "nes-diagnostics.log");
            FileWriter writer = new FileWriter(file, true);
            writer.write(timestamp() + " pid=" + Process.myPid() + " " + message + "\n");
            if (throwable != null) {
                throwable.printStackTrace(new PrintWriter(writer));
                writer.write("\n");
            }
            writer.close();
        } catch (Throwable ignored) {
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
