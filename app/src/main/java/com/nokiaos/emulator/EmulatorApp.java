package com.nokiaos.emulator;

import android.app.Application;

/** Holds a single EmulatorEngine instance shared across all activities. */
public class EmulatorApp extends Application {
    private EmulatorEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new EmulatorEngine();
    }

    public EmulatorEngine getEngine() {
        return engine;
    }
}
