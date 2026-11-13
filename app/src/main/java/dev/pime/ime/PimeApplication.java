package dev.pime.ime;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/** Applies the stored theme before any activity or input view is created. */
public final class PimeApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this));
    }
}
