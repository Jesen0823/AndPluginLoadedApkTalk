package com.jesen.loadedapk_host;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {

    private Button startPlugin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startPlugin = findViewById(R.id.startPlugin);
        startPlugin.setOnClickListener(view -> {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.jesen.loadedapk_plugin"
                    ,"com.jesen.loadedapk_plugin.PluginActivity"));
            startActivity(intent);
        });
    }
}