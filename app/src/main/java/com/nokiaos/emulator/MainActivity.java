package com.nokiaos.emulator;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EmulatorEngine engine;
    private PhoneView phoneView;
    private TextView tvSignal, tvBattery, tvClock;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = ((EmulatorApp) getApplication()).getEngine();
        phoneView = findViewById(R.id.phoneView);
        phoneView.bind(engine.core);
        tvSignal = findViewById(R.id.tvSignal);
        tvBattery = findViewById(R.id.tvBattery);
        tvClock = findViewById(R.id.tvClock);

        findViewById(R.id.btnRun).setOnClickListener(v -> engine.start());
        findViewById(R.id.btnPause).setOnClickListener(v -> engine.pause());
        findViewById(R.id.btnStep).setOnClickListener(v -> engine.step());
        findViewById(R.id.btnReset).setOnClickListener(v -> {
            engine.reset();
            Toast.makeText(this, "Emulator reset", Toast.LENGTH_SHORT).show();
        });

        SeekBar speed = findViewById(R.id.seekSpeed);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                engine.speedMultiplier = Math.max(0.1f, progress / 10f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        findViewById(R.id.btnDebug).setOnClickListener(v -> startActivity(new Intent(this, DebugActivity.class)));
        findViewById(R.id.btnFirmware).setOnClickListener(v -> startActivity(new Intent(this, FirmwareActivity.class)));
        findViewById(R.id.btnProfile).setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));

        buildKeypad();

        engine.setListener(new EmulatorEngine.Listener() {
            @Override public void onFrame() {
                uiHandler.post(() -> {
                    phoneView.refresh();
                    updateStatusBar();
                });
            }
            @Override public void onHalt(String reason) {
                uiHandler.post(() -> Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show());
            }
        });

        updateStatusBar();
    }

    private void buildKeypad() {
        GridLayout grid = findViewById(R.id.keypadGrid);
        grid.removeAllViews();
        // Explicit bit map per spec: bits 0-19 = 1-9,Up,Down,Left,Right,*,0,#,SoftL,Menu,SoftR,OK
        String[] labels = {"1","2","3","Up","4","5","6","Down","7","8","9","Left","*","0","#","Right","SoftL","Menu","SoftR","OK"};
        for (int i = 0; i < labels.length; i++) {
            final int bit = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(11f);
            b.setBackgroundColor(Color.parseColor("#444444"));
            b.setTextColor(Color.WHITE);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(i % 4, 1f);
            lp.rowSpec = GridLayout.spec(i / 4);
            lp.setMargins(2, 2, 2, 2);
            b.setLayoutParams(lp);
            b.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    engine.core.setKeyState(bit, true);
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    engine.core.setKeyState(bit, false);
                    return true;
                }
                return false;
            });
            grid.addView(b);
        }
    }

    private void updateStatusBar() {
        tvSignal.setText("Signal: " + (engine.core.readWord(CorePeripherals.KEYPAD_SCAN) == 0xFFFFF ? "idle" : "key"));
        int battRaw = engine.core.readWord(CorePeripherals.BATT_VOLTAGE);
        tvBattery.setText("Batt: " + battRaw + "mV");
        tvClock.setText(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
    }

    @Override
    protected void onPause() {
        super.onPause();
        engine.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusBar();
    }
}
