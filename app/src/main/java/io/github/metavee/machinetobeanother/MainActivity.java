package io.github.metavee.machinetobeanother;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.text.InputType;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        askCameraPermission();

    }

    public boolean askCameraPermission() {
        // https://stackoverflow.com/a/41374870
        boolean has_permission = ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (!has_permission) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    1);
        }

        return has_permission;
    }

    public void startView(View view) {
        if (!this.askCameraPermission()) {
            Toast.makeText(this, "Permission denied to read camera", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, TextureTestActivity.class);
        intent.putExtra("mode", TextureTestActivity.MODE_VIEW);
        startActivity(intent);
    }

    public void startRecord(View view) {
        if (!this.askCameraPermission()) {
            Toast.makeText(this, "Permission denied to read camera", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!this.isExternalStorageWritable()) {
            Toast.makeText(this, "External storage unavailable", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, TextureTestActivity.class);
        intent.putExtra("mode", TextureTestActivity.MODE_RECORD);
        startActivity(intent);
    }

    /**
     * Prompts for the viewer's Cardboard calibration URL (the one encoded in the QR code on
     * the headset, e.g. https://google.com/cardboard/cfg?p=...) and saves it as the active
     * viewer profile. This is the same profile format the official Cardboard app uses; the
     * stereo renderer reads it via {@link CardboardProfile}.
     */
    public void calibrate(View view) {
        final EditText input = new EditText(this);
        input.setHint("https://google.com/cardboard/cfg?p=…  or a QR short link");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
                .setTitle("Viewer calibration")
                .setMessage("Paste the URL from your headset's QR code. A short link "
                        + "(e.g. goo.gl/…) is fine — the app will follow it to the profile.")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) ->
                        resolveAndSaveProfile(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Saves the calibration for a pasted URL. If the URL already carries the profile ({@code
     * p} parameter) it is saved immediately; otherwise the URL is followed through its HTTP
     * redirects (on a background thread) until the {@code cfg?p=} URL is found — so a QR short
     * link like {@code goo.gl/…} works directly, without the user resolving it by hand.
     */
    private void resolveAndSaveProfile(String url) {
        if (url.isEmpty()) {
            return;
        }

        byte[] direct = CardboardProfile.deviceParamsFromUri(url);
        if (direct != null && CardboardProfile.parse(direct) != null) {
            saveProfile(direct);
            return;
        }

        Toast.makeText(this, "Resolving link…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            byte[] params = resolveDeviceParams(url);
            runOnUiThread(() -> {
                if (params != null) {
                    saveProfile(params);
                } else {
                    Toast.makeText(this, "Couldn't find a Cardboard profile at that link",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void saveProfile(byte[] params) {
        CardboardProfile.save(this, params);
        Toast.makeText(this, "Calibration saved", Toast.LENGTH_SHORT).show();
    }

    /**
     * Follows HTTP redirects starting from {@code startUrl} until it reaches a URL whose
     * {@code p} query parameter is a parseable Cardboard profile, and returns those bytes.
     * Stops as soon as the profile is found (so it never follows the Cardboard {@code cfg}
     * URL onward to the "get Cardboard" landing page). Returns null if none is found.
     *
     * <p>Must not be called on the main thread.
     */
    private static byte[] resolveDeviceParams(String startUrl) {
        String current = startUrl;
        try {
            for (int hop = 0; hop < 10 && current != null; hop++) {
                byte[] params = CardboardProfile.deviceParamsFromUri(current);
                if (params != null && CardboardProfile.parse(params) != null) {
                    return params;
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                conn.disconnect();

                if (code >= 300 && code < 400 && location != null) {
                    current = new URL(new URL(current), location).toString(); // resolve relative
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to resolve calibration URL", e);
        }
        return null;
    }

    public void startPlayback(View view) {
        if (!this.isExternalStorageWritable()) {
            Toast.makeText(this, "External storage unavailable", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, VideoListActivity.class);
        startActivity(intent);
    }

    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }
}
