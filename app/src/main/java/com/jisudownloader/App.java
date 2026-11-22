package com.jisudownloader;

import android.app.Application;

import com.jisudownloader.util.Prefs;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
    }
}