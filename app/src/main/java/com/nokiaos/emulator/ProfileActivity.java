package com.nokiaos.emulator;

import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ProfileActivity extends AppCompatActivity {

    private static final String[] PROFILE_FILES = {
            "combo1.json", "combo2.json", "combo3.json", "combo4.json", "combo5.json"
    };

    private TextView tvDetail, tvMemMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        tvDetail = findViewById(R.id.tvProfileDetail);
        tvMemMap = findViewById(R.id.tvMemMap);
        tvMemMap.setText(
                "0x00000000-0x0007FFFF  Boot ROM (512KB, RO)\n" +
                "0x00080000-0x000FFFFF  System RAM (512KB)\n" +
                "0x00100000-0x001FFFFF  Extended RAM (1MB)\n" +
                "0x80000000-0x80000FFF  Core MMIO (4KB)\n" +
                "0xA0000000-0xA0000FFF  Modem MMIO (4KB)\n" +
                "0xC0000000-0xCFFFFFFF  NAND Flash (256MB)"
        );

        Spinner spinner = findViewById(R.id.spinnerProfiles);
        String[] names = {"MT6261 Standard", "MT6261 Low-Clock", "MT6261 High-Flash", "MT6261 Minimal RAM", "MT6261 Debug Build"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                showProfile(PROFILE_FILES[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        showProfile(PROFILE_FILES[0]);
    }

    private void showProfile(String fileName) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open("profiles/" + fileName), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject obj = new JSONObject(sb.toString());
            StringBuilder out = new StringBuilder();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.append(k).append(": ").append(obj.get(k)).append("\n");
            }
            tvDetail.setText(out.toString());
        } catch (Exception e) {
            tvDetail.setText("Failed to load profile: " + e.getMessage());
        }
    }
}
