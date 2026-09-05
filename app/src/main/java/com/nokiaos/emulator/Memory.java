package com.nokiaos.emulator;

/**
 * Memory bus for the MT6261-style feature-phone hardware model.
 *
 * Map:
 *  0x00000000-0x0007FFFF  Boot ROM      (512KB, read-only)
 *  0x00080000-0x000FFFFF  System RAM    (512KB)
 *  0x00100000-0x001FFFFF  Extended RAM  (1MB)
 *  0x80000000-0x80000FFF  Core MMIO     (4KB)
 *  0xA0000000-0xA0000FFF  Modem MMIO    (4KB)
 *  0xC0000000-0xCFFFFFFF  NAND Flash    (256MB, backed by a sparse array to stay lightweight)
 *
 * Unmapped reads return 0xFFFFFFFF (matches "unmapped access returns default").
 * Unmapped/illegal writes are silently ignored except where they should raise
 * a Data Abort (delegated back to the CPU by returning a fault flag).
 */
public class Memory {

    public static final long ROM_BASE = 0x00000000L;
    public static final long ROM_SIZE = 0x00080000L;
    public static final long RAM_BASE = 0x00080000L;
    public static final long RAM_SIZE = 0x00080000L;
    public static final long XRAM_BASE = 0x00100000L;
    public static final long XRAM_SIZE = 0x00100000L;
    public static final long CORE_MMIO_BASE = 0x80000000L;
    public static final long CORE_MMIO_SIZE = 0x1000L;
    public static final long MODEM_MMIO_BASE = 0xA0000000L;
    public static final long MODEM_MMIO_SIZE = 0x1000L;
    public static final long FLASH_BASE = 0xC0000000L;
    public static final long FLASH_SIZE = 0x10000000L; // logically 256MB, stored sparsely

    private final byte[] rom = new byte[(int) ROM_SIZE];
    private final byte[] ram = new byte[(int) (RAM_SIZE + XRAM_SIZE)]; // contiguous RAM+XRAM
    private final java.util.Map<Integer, Byte> flash = new java.util.HashMap<>();

    public final CorePeripherals core;
    public final ModemPeripherals modem;

    public boolean lastAccessFaulted = false;

    public Memory(CorePeripherals core, ModemPeripherals modem) {
        this.core = core;
        this.modem = modem;
    }

    public void reset() {
        java.util.Arrays.fill(ram, (byte) 0);
        lastAccessFaulted = false;
    }

    public void loadFirmware(byte[] data) {
        int len = Math.min(data.length, rom.length);
        System.arraycopy(data, 0, rom, 0, len);
    }

    private boolean inRange(long addr, long base, long size) {
        long a = addr & 0xFFFFFFFFL;
        return a >= base && a < base + size;
    }

    public int read32(long addr) {
        return (readByteRaw(addr) & 0xFF)
                | ((readByteRaw(addr + 1) & 0xFF) << 8)
                | ((readByteRaw(addr + 2) & 0xFF) << 16)
                | ((readByteRaw(addr + 3) & 0xFF) << 24);
    }

    public int read16(long addr) {
        return (readByteRaw(addr) & 0xFF) | ((readByteRaw(addr + 1) & 0xFF) << 8);
    }

    public int read8(long addr) {
        return readByteRaw(addr) & 0xFF;
    }

    public void write32(long addr, int val) {
        writeByteRaw(addr, (byte) val);
        writeByteRaw(addr + 1, (byte) (val >> 8));
        writeByteRaw(addr + 2, (byte) (val >> 16));
        writeByteRaw(addr + 3, (byte) (val >> 24));
    }

    public void write16(long addr, int val) {
        writeByteRaw(addr, (byte) val);
        writeByteRaw(addr + 1, (byte) (val >> 8));
    }

    public void write8(long addr, int val) {
        writeByteRaw(addr, (byte) val);
    }

    private byte readByteRaw(long addr) {
        lastAccessFaulted = false;
        addr &= 0xFFFFFFFFL;
        if (inRange(addr, ROM_BASE, ROM_SIZE)) {
            return rom[(int) (addr - ROM_BASE)];
        }
        if (inRange(addr, RAM_BASE, RAM_SIZE + XRAM_SIZE)) {
            return ram[(int) (addr - RAM_BASE)];
        }
        if (inRange(addr, CORE_MMIO_BASE, CORE_MMIO_SIZE)) {
            return (byte) core.readByte((int) (addr - CORE_MMIO_BASE));
        }
        if (inRange(addr, MODEM_MMIO_BASE, MODEM_MMIO_SIZE)) {
            return (byte) modem.readByte((int) (addr - MODEM_MMIO_BASE));
        }
        if (inRange(addr, FLASH_BASE, FLASH_SIZE)) {
            Byte b = flash.get((int) (addr - FLASH_BASE));
            return b == null ? (byte) 0xFF : b;
        }
        // Unmapped access -> fault, default value
        lastAccessFaulted = true;
        return (byte) 0xFF;
    }

    private void writeByteRaw(long addr, byte val) {
        addr &= 0xFFFFFFFFL;
        if (inRange(addr, ROM_BASE, ROM_SIZE)) {
            return; // ROM writes ignored
        }
        if (inRange(addr, RAM_BASE, RAM_SIZE + XRAM_SIZE)) {
            ram[(int) (addr - RAM_BASE)] = val;
            return;
        }
        if (inRange(addr, CORE_MMIO_BASE, CORE_MMIO_SIZE)) {
            core.writeByte((int) (addr - CORE_MMIO_BASE), val & 0xFF);
            return;
        }
        if (inRange(addr, MODEM_MMIO_BASE, MODEM_MMIO_SIZE)) {
            modem.writeByte((int) (addr - MODEM_MMIO_BASE), val & 0xFF);
            return;
        }
        if (inRange(addr, FLASH_BASE, FLASH_SIZE)) {
            flash.put((int) (addr - FLASH_BASE), val);
            return;
        }
        lastAccessFaulted = true;
    }

    /** For the hex-dump / memory viewer UI. Returns bytes, clamped to valid regions. */
    public byte[] dump(long addr, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = readByteRaw(addr + i);
        return out;
    }
}
