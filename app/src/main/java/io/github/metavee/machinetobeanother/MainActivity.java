package io.github.metavee.machinetobeanother;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
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
