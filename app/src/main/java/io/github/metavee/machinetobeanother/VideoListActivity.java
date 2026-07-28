package io.github.metavee.machinetobeanother;

import android.content.Intent;
import android.os.Environment;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.io.File;

// with help from https://github.com/codepath/android_guides/wiki/Using-an-ArrayAdapter-with-ListView

public class VideoListActivity extends AppCompatActivity implements AdapterView.OnItemClickListener {

    private File[] items;

    ArrayAdapter<File> itemsAdapter;
    ListView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_list);

        File outdir = getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        // listFiles() returns null if the directory does not exist yet (e.g. a
        // fresh install that has never recorded a video), which would crash the
        // ArrayAdapter below, so fall back to an empty list.
        items = outdir != null ? outdir.listFiles() : null;
        if (items == null) {
            items = new File[0];
        }

        itemsAdapter = new ArrayAdapter<File>(this, R.layout.simple_list_item_1, items);

        list = (ListView) findViewById(R.id.video_list);
        list.setAdapter(itemsAdapter);

        list.setOnItemClickListener(this);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        String item = items[position].toString();
        Intent intent = new Intent(this, TextureTestActivity.class);
        intent.putExtra("mode", TextureTestActivity.MODE_PLAYBACK);
        intent.putExtra("filename", item);
        startActivity(intent);
    }
}
