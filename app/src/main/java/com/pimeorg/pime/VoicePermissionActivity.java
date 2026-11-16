package com.pimeorg.pime;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * Transparent launcher used by the IME to ask for the microphone permission.
 * Input method services cannot request permissions themselves, so the toolbar
 * opens this activity for one dialog and finishes immediately.
 */
public final class VoicePermissionActivity extends Activity {
    private static final int REQUEST = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions,
                                           int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            final Intent intent = new Intent(PimeImeService.ACTION_VOICE_READY);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
        finish();
    }
}
