package com.nokiaos.emulator;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class DebugActivity extends AppCompatActivity {

    private EmulatorEngine engine;
    private TextView tvRegisters, tvMemory, tvTrace;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);
        engine = ((EmulatorApp) getApplication()).getEngine();

        tvRegisters = findViewById(R.id.tvRegisters);
        tvMemory = findViewById(R.id.tvMemory);
        tvTrace = findViewById(R.id.tvTrace);

        findViewById(R.id.btnRefresh).setOnClickListener(v -> refresh());

        EditText etBp = findViewById(R.id.etBreakpoint);
        findViewById(R.id.btnAddBp).setOnClickListener(v -> {
            try {
                int addr = (int) Long.parseLong(etBp.getText().toString().trim(), 16);
                engine.addBreakpoint(addr);
                Toast.makeText(this, "Breakpoint set at 0x" + Integer.toHexString(addr), Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid hex address", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btnClearBp).setOnClickListener(v -> {
            engine.getBreakpoints().clear();
            Toast.makeText(this, "Breakpoints cleared", Toast.LENGTH_SHORT).show();
        });

        refresh();
    }

    private void refresh() {
        Cpu cpu = engine.cpu;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("R%-2d=%08X  ", i, cpu.r[i]));
            if (i % 4 == 3) sb.append("\n");
        }
        sb.append(String.format("CPSR=%08X  SPSR=%08X\n", cpu.cpsr, cpu.getSpsr()));
        sb.append(String.format("N=%d Z=%d C=%d V=%d I=%d F=%d T=%d MODE=0x%02X\n",
                b(cpu.flag(Cpu.BIT_N)), b(cpu.flag(Cpu.BIT_Z)), b(cpu.flag(Cpu.BIT_C)), b(cpu.flag(Cpu.BIT_V)),
                b(cpu.flag(Cpu.BIT_I)), b(cpu.flag(Cpu.BIT_F)), b(cpu.flag(Cpu.BIT_T)), cpu.mode()));
        sb.append("Instructions retired: ").append(cpu.instructionsRetired);
        tvRegisters.setText(sb.toString());

        byte[] dump = engine.memory.dump(cpu.r[15] & ~0xF, 128);
        StringBuilder mem = new StringBuilder();
        int base = cpu.r[15] & ~0xF;
        for (int row = 0; row < dump.length / 16; row++) {
            mem.append(String.format("%08X: ", base + row * 16));
            for (int col = 0; col < 16; col++) {
                mem.append(String.format("%02X ", dump[row * 16 + col]));
            }
            mem.append("\n");
        }
        tvMemory.setText(mem.toString());

        String trace = engine.traceLog.toString();
        int len = trace.length();
        tvTrace.setText(trace.substring(Math.max(0, len - 2000)));
    }

    private int b(boolean v) { return v ? 1 : 0; }
}
