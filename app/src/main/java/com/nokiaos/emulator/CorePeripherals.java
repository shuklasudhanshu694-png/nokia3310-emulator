package com.nokiaos.emulator;

/**
 * Core MMIO block at 0x80000000, implementing the register map from the spec:
 * SYS_CTRL, CLOCK_CTRL, IRQ_*, TIMER_*, WATCHDOG, UART_*, GPIO_*, RTC_*, DMA_*,
 * KEYPAD_*, LCD_*, POWER_CTRL, BATT_VOLTAGE, VIBRATOR, BEEPER_*, DEBUG_MSG,
 * FLASH_ADDR/DATA/CMD.
 *
 * This is a register-accurate model (offsets, reset values, and the bits the
 * spec calls out) rather than a cycle-accurate silicon simulation.
 */
public class CorePeripherals {

    // Register offsets
    public static final int SYS_CTRL = 0x000;
    public static final int CLOCK_CTRL = 0x008;
    public static final int IRQ_ENABLE = 0x010;
    public static final int IRQ_PENDING = 0x014;
    public static final int IRQ_CLEAR = 0x018;
    public static final int TIMER_LOAD = 0x020;
    public static final int TIMER_VALUE = 0x024;
    public static final int TIMER_CTRL = 0x028;
    public static final int WATCHDOG = 0x02C;
    public static final int UART_DATA = 0x030;
    public static final int UART_STATUS = 0x034;
    public static final int UART_BAUD = 0x038;
    public static final int GPIO_DIR = 0x040;
    public static final int GPIO_DATA = 0x044;
    public static final int RTC_SECONDS = 0x050;
    public static final int RTC_ALARM = 0x054;
    public static final int DMA_CTRL = 0x05C;
    public static final int DMA_SRC = 0x060;
    public static final int DMA_DST = 0x064;
    public static final int DMA_LEN = 0x068;
    public static final int KEYPAD_SCAN = 0x06C;
    public static final int KEYPAD_DEBOUNCE = 0x070;
    public static final int LCD_CTRL = 0x078;
    public static final int LCD_CONTRAST = 0x07C;
    public static final int LCD_REFRESH = 0x080;
    public static final int POWER_CTRL = 0x084;
    public static final int BATT_VOLTAGE = 0x088;
    public static final int VIBRATOR = 0x090;
    public static final int BEEPER_FREQ = 0x094;
    public static final int BEEPER_DURATION = 0x098;
    public static final int DEBUG_MSG = 0x09C;
    public static final int FLASH_ADDR = 0x0A0;
    public static final int FLASH_DATA = 0x0A4;
    public static final int FLASH_CMD = 0x0A8;

    // IRQ lines
    public static final int IRQ_SYSTICK = 0;
    public static final int IRQ_KEYPAD = 1;
    public static final int IRQ_MODEM_RX = 2;
    public static final int IRQ_DMA_DONE = 3;
    public static final int IRQ_RTC_ALARM = 4;
    public static final int IRQ_UART = 5;
    public static final int IRQ_DISPLAY = 6;
    public static final int IRQ_CAMERA = 7;
    public static final int IRQ_AUDIO = 8;
    public static final int IRQ_BATTERY_LOW = 9;

    private final int[] reg = new int[0x100];
    public final byte[] framebuffer = new byte[320 * 480]; // simplified 1 byte/px luminance model
    public StringBuilder debugLog = new StringBuilder();
    public int keypadBits = 0xFFFFF; // active-low: 1 = not pressed

    public CorePeripherals() {
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(reg, 0);
        setWord(TIMER_LOAD, 0xFFFF);
        setWord(TIMER_VALUE, 0xFFFF);
        setWord(UART_STATUS, 0x2);
        setWord(UART_BAUD, 0x1C);
        setWord(KEYPAD_SCAN, 0xFFFF);
        setWord(KEYPAD_DEBOUNCE, 0x14);
        setWord(LCD_CONTRAST, 0x40);
        setWord(BATT_VOLTAGE, 0xFA0);
        keypadBits = 0xFFFFF;
    }

    private void setWord(int off, int val) {
        reg[off] = val;
    }

    public int readWord(int off) {
        if (off == KEYPAD_SCAN) return keypadBits;
        if (off >= 0 && off < reg.length) return reg[off];
        return 0xFFFFFFFF;
    }

    public void writeWord(int off, int val) {
        switch (off) {
            case IRQ_CLEAR:
                reg[IRQ_PENDING] &= ~val;
                return;
            case LCD_REFRESH:
                // write-triggers a refresh; UI reads framebuffer separately
                return;
            case DEBUG_MSG:
                debugLog.append((char) (val & 0xFF));
                return;
            default:
                reg[off] = val;
        }
    }

    // Byte-granular access used by Memory for unaligned/byte ops.
    public int readByte(int off) {
        int wordOff = off & ~3;
        int shift = (off & 3) * 8;
        return (readWord(wordOff) >> shift) & 0xFF;
    }

    public void writeByte(int off, int val) {
        int wordOff = off & ~3;
        // DEBUG_MSG appends every byte written to a log, so it must react to
        // exactly one byte lane per logical write -- a 32-bit STR decomposed
        // into 4 byte writes would otherwise append 4 characters instead of 1.
        // (IRQ_CLEAR and LCD_REFRESH don't have this problem: clearing bits or
        // triggering a refresh multiple times from the same word write is
        // harmless, so they use the normal merge path below.)
        if (wordOff == DEBUG_MSG) {
            if (off == wordOff) {
                debugLog.append((char) (val & 0xFF));
            }
            return;
        }
        int shift = (off & 3) * 8;
        int cur = readWord(wordOff);
        cur = (cur & ~(0xFF << shift)) | ((val & 0xFF) << shift);
        writeWord(wordOff, cur);
    }

    public void raiseIrq(int line) {
        reg[IRQ_PENDING] |= (1 << line);
    }

    public boolean irqAsserted() {
        return (reg[IRQ_PENDING] & reg[IRQ_ENABLE]) != 0;
    }

    /** Called once per emulated millisecond by the run loop. */
    public void tickMillisecond() {
        raiseIrq(IRQ_SYSTICK);
        int ctrl = reg[TIMER_CTRL];
        if ((ctrl & 0x1) != 0) { // enabled
            int val = reg[TIMER_VALUE] - 1;
            if (val <= 0) {
                boolean autoReload = (ctrl & 0x2) != 0;
                val = autoReload ? reg[TIMER_LOAD] : 0;
                if (!autoReload) reg[TIMER_CTRL] &= ~0x1; // one-shot stops
            }
            reg[TIMER_VALUE] = val;
        }
        reg[RTC_SECONDS] = reg[RTC_SECONDS]; // seconds advanced externally by the activity loop
    }

    public void setKeyState(int bit, boolean pressed) {
        if (pressed) {
            keypadBits &= ~(1 << bit);
            raiseIrq(IRQ_KEYPAD);
        } else {
            keypadBits |= (1 << bit);
        }
    }
}
