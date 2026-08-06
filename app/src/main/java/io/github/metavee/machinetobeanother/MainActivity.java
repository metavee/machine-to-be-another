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
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

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
        input.setHint("https://google.com/cardboard/cfg?p=...");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
                .setTitle("Viewer calibration")
                .setMessage("Paste the Cardboard viewer URL from your headset's QR code.")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String url = input.getText().toString();
                    byte[] params = CardboardProfile.deviceParamsFromUri(url);
                    if (params == null || CardboardProfile.parse(params) == null) {
                        Toast.makeText(this, "Not a valid Cardboard viewer URL", Toast.LENGTH_LONG).show();
                        return;
                    }
                    CardboardProfile.save(this, params);
                    Toast.makeText(this, "Calibration saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
