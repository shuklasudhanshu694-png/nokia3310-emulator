package com.nokiaos.emulator;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Lets the user pick a raw .bin firmware image (Storage Access Framework),
 * or load the bundled test firmware from assets, and copies it into the
 * emulated Boot ROM. Also supports Intel HEX (.hex) parsing.
 */
public class FirmwareActivity extends AppCompatActivity {

    private EmulatorEngine engine;
    private TextView tvInfo, tvChecksum;
    private ActivityResultLauncher<String[]> picker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_firmware);
        engine = ((EmulatorApp) getApplication()).getEngine();
        tvInfo = findViewById(R.id.tvInfo);
        tvChecksum = findViewById(R.id.tvChecksum);

        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) loadFromUri(uri);
        });

        findViewById(R.id.btnPick).setOnClickListener(v ->
                picker.launch(new String[]{"application/octet-stream", "*/*"}));

        findViewById(R.id.btnLoadTest).setOnClickListener(v -> loadBundledTest());

        findViewById(R.id.btnUnload).setOnClickListener(v -> {
            engine.reset();
            tvInfo.setText("No firmware loaded");
            tvChecksum.setText("");
            Toast.makeText(this, "Firmware unloaded, emulator reset", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            byte[] data = readAll(is);
            String name = uri.getLastPathSegment();
            byte[] binary = name != null && name.toLowerCase().endsWith(".hex")
                    ? IntelHexParser.parse(data) : data;
            applyFirmware(binary, name);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to read file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadBundledTest() {
        try (InputStream is = getAssets().open("firmware/test_firmware.bin")) {
            byte[] data = readAll(is);
            applyFirmware(data, "test_firmware.bin (bundled)");
        } catch (IOException e) {
            Toast.makeText(this, "Bundled firmware missing: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyFirmware(byte[] data, String name) {
        engine.reset();
        engine.loadFirmware(data);
        tvInfo.setText("Loaded: " + name + "\nSize: " + data.length + " bytes");
        tvChecksum.setText("SHA-256: " + sha256(data));
        Toast.makeText(this, "Firmware loaded", Toast.LENGTH_SHORT).show();
    }

    private byte[] readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }
}
