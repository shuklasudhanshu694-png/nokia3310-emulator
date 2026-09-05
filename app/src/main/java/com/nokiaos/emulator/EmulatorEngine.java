package com.nokiaos.emulator;

import java.util.HashSet;
import java.util.Set;

/**
 * Owns the CPU/memory/peripheral state and drives execution on a dedicated
 * background thread so the UI thread is never blocked (per lifecycle /
 * threading requirements). All public control methods are safe to call
 * from the UI thread.
 */
public class EmulatorEngine {

    public final CorePeripherals core = new CorePeripherals();
    public final ModemPeripherals modem = new ModemPeripherals();
    public final Memory memory = new Memory(core, modem);
    public final Cpu cpu = new Cpu(memory);

    private volatile boolean running = false;
    private volatile boolean stepRequested = false;
    private Thread thread;
    private final Set<Integer> breakpoints = new HashSet<>();
    public final StringBuilder traceLog = new StringBuilder();
    public volatile float speedMultiplier = 1.0f;

    public interface Listener {
        void onFrame();
        void onHalt(String reason);
    }

    private Listener listener;
    public void setListener(Listener l) { this.listener = l; }

    public void loadFirmware(byte[] data) {
        memory.loadFirmware(data);
    }

    public void reset() {
        stop();
        core.reset();
        modem.reset();
        memory.reset();
        cpu.reset();
        traceLog.setLength(0);
    }

    public void addBreakpoint(int addr) { breakpoints.add(addr); }
    public void removeBreakpoint(int addr) { breakpoints.remove(addr); }
    public Set<Integer> getBreakpoints() { return breakpoints; }

    public void start() {
        if (running) return;
        running = true;
        cpu.halted = false;
        thread = new Thread(this::runLoop, "EmulatorThread");
        thread.setDaemon(true);
        thread.start();
    }

    public void pause() {
        running = false;
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(200); } catch (InterruptedException ignored) {}
        }
    }

    public void step() {
        stepRequested = true;
        if (!running) {
            executeOne();
            if (listener != null) listener.onFrame();
        }
    }

    private void runLoop() {
        long lastMs = System.currentTimeMillis();
        int cyclesThisMs = 0;
        final int CYCLES_PER_MS = 52000 / 1000; // 52MHz / 1000 ms, simplified

        while (running) {
            if (breakpoints.contains(cpu.r[15])) {
                running = false;
                if (listener != null) listener.onHalt("Breakpoint hit at 0x" + Integer.toHexString(cpu.r[15]));
                break;
            }
            int cycles = executeOne();
            cyclesThisMs += cycles;

            if (cyclesThisMs >= CYCLES_PER_MS) {
                cyclesThisMs = 0;
                core.tickMillisecond();
                long now = System.currentTimeMillis();
                if (listener != null && (now - lastMs) >= (16 / Math.max(0.1f, speedMultiplier))) {
                    lastMs = now;
                    listener.onFrame();
                }
                if (speedMultiplier < 50f) {
                    try { Thread.sleep((long) Math.max(0, 1 / Math.max(0.05f, speedMultiplier))); }
                    catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private int executeOne() {
        if (traceLog.length() < 20000) {
            traceLog.append(String.format("PC=%08X CPSR=%08X\n", cpu.r[15], cpu.cpsr));
        }
        return cpu.step();
    }

    public boolean isRunning() { return running; }
}
