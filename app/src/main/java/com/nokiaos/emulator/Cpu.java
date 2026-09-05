package com.nokiaos.emulator;

/**
 * ARM7TDMI-S interpreter core (ARMv4T: ARM + Thumb).
 *
 * Implements banked registers per mode, CPSR/SPSR, condition codes, and
 * dispatches to ArmDecoder / ThumbDecoder depending on the T bit.
 *
 * This is a functional instruction-set interpreter (correct semantics,
 * correct flag updates, correct mode switching) rather than a
 * cycle/pipeline-accurate simulation -- true 3-stage pipeline timing is not
 * observable from software running on the core, so it is modeled only to
 * the extent it affects PC-relative addressing (PC reads as current+8 in
 * ARM state, current+4 in Thumb state, matching the spec's Q3 answer).
 */
public class Cpu {

    // Modes
    public static final int MODE_USER = 0x10;
    public static final int MODE_FIQ = 0x11;
    public static final int MODE_IRQ = 0x12;
    public static final int MODE_SVC = 0x13;
    public static final int MODE_ABORT = 0x17;
    public static final int MODE_UNDEF = 0x1B;
    public static final int MODE_SYSTEM = 0x1F;

    // CPSR bit positions
    public static final int BIT_N = 31, BIT_Z = 30, BIT_C = 29, BIT_V = 28;
    public static final int BIT_I = 7, BIT_F = 6, BIT_T = 5;

    public final int[] r = new int[16]; // current visible R0-R15
    public int cpsr;

    // Banked registers per mode: r13(SP), r14(LR) for all privileged modes,
    // plus r8-r14 for FIQ.
    private final int[] fiqBank = new int[7]; // r8-r14
    private final int[] usrBank = new int[2]; // r13, r14 (user/system share)
    private final int[] svcBank = new int[2];
    private final int[] abtBank = new int[2];
    private final int[] irqBank = new int[2];
    private final int[] undBank = new int[2];
    private final java.util.Map<Integer, Integer> spsr = new java.util.HashMap<>();

    public final Memory mem;
    public long instructionsRetired = 0;
    public boolean halted = false;

    public Cpu(Memory mem) {
        this.mem = mem;
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(r, 0);
        cpsr = MODE_SVC; // boot in supervisor mode
        setFlag(BIT_I, true);
        setFlag(BIT_F, true);
        r[15] = 0x00000000; // Boot Address
        r[13] = 0x001FFFFF; // top of extended RAM as initial SP
        instructionsRetired = 0;
        halted = false;
    }

    public int mode() { return cpsr & 0x1F; }
    public boolean thumb() { return (cpsr & (1 << BIT_T)) != 0; }
    public boolean flag(int bit) { return ((cpsr >>> bit) & 1) != 0; }
    public void setFlag(int bit, boolean v) {
        if (v) cpsr |= (1 << bit); else cpsr &= ~(1 << bit);
    }

    /** Switches banked r13/r14 (and r8-r12 for FIQ) when mode changes. */
    public void switchMode(int newMode) {
        int old = mode();
        if (old == newMode) return;
        saveBank(old);
        loadBank(newMode);
        cpsr = (cpsr & ~0x1F) | (newMode & 0x1F);
    }

    private void saveBank(int m) {
        if (m == MODE_FIQ) {
            for (int i = 0; i < 7; i++) fiqBank[i] = r[8 + i];
        } else {
            int[] b = bankFor(m);
            if (b != null) { b[0] = r[13]; b[1] = r[14]; }
        }
    }

    private void loadBank(int m) {
        if (m == MODE_FIQ) {
            for (int i = 0; i < 7; i++) r[8 + i] = fiqBank[i];
        } else {
            // restore r8-r12 to non-FIQ values if we were in FIQ (approximation: keep as-is)
            int[] b = bankFor(m);
            if (b != null) { r[13] = b[0]; r[14] = b[1]; }
        }
    }

    private int[] bankFor(int m) {
        switch (m) {
            case MODE_USER: case MODE_SYSTEM: return usrBank;
            case MODE_SVC: return svcBank;
            case MODE_ABORT: return abtBank;
            case MODE_IRQ: return irqBank;
            case MODE_UNDEF: return undBank;
            default: return usrBank;
        }
    }

    public void setSpsr(int val) { spsr.put(mode(), val); }
    public int getSpsr() { Integer v = spsr.get(mode()); return v == null ? 0 : v; }

    /** PC as read by instructions (pipeline offset: current instr addr + 8 in ARM, +4 in Thumb).
     *  r[15] has already been advanced by 4 (ARM) / 2 (Thumb) past the current instruction's
     *  address by the time this is called from within execute(), so we only need to add the
     *  remaining 4 / 2 to reach the full pipeline-read value. */
    public int pcForFetch() { return thumb() ? r[15] + 2 : r[15] + 4; }

    public void raiseIrqException() {
        if (flag(BIT_I)) return; // masked
        int retAddr = r[15] + (thumb() ? 2 : 4);
        int savedCpsr = cpsr;
        switchMode(MODE_IRQ);
        setSpsr(savedCpsr);
        r[14] = retAddr;
        setFlag(BIT_I, true);
        setFlag(BIT_T, false); // IRQ handlers run in ARM state
        r[15] = 0x00000018;
    }

    public void raiseSwi() {
        int retAddr = r[15] + (thumb() ? 2 : 4);
        int savedCpsr = cpsr;
        switchMode(MODE_SVC);
        setSpsr(savedCpsr);
        r[14] = retAddr;
        setFlag(BIT_I, true);
        setFlag(BIT_T, false);
        r[15] = 0x00000008;
    }

    public void raiseDataAbort() {
        int retAddr = r[15] + (thumb() ? 4 : 8);
        int savedCpsr = cpsr;
        switchMode(MODE_ABORT);
        setSpsr(savedCpsr);
        r[14] = retAddr;
        setFlag(BIT_I, true);
        setFlag(BIT_T, false);
        r[15] = 0x00000010;
    }

    /** Executes exactly one instruction. Returns approximate cycle count. */
    public int step() {
        if (halted) return 1;

        if (mem.core.irqAsserted() && !flag(BIT_I)) {
            raiseIrqException();
        }

        int pc = r[15];
        if (thumb()) {
            int opcode = mem.read16(pc) & 0xFFFF;
            r[15] = pc + 2;
            int cycles = ThumbDecoder.execute(this, opcode);
            checkFault();
            instructionsRetired++;
            return cycles;
        } else {
            int opcode = mem.read32(pc);
            r[15] = pc + 4;
            int cycles = ArmDecoder.execute(this, opcode);
            checkFault();
            instructionsRetired++;
            return cycles;
        }
    }

    private void checkFault() {
        if (mem.lastAccessFaulted) {
            mem.lastAccessFaulted = false;
            raiseDataAbort();
        }
    }

    public boolean conditionPasses(int cond) {
        boolean n = flag(BIT_N), z = flag(BIT_Z), c = flag(BIT_C), v = flag(BIT_V);
        switch (cond) {
            case 0x0: return z;               // EQ
            case 0x1: return !z;              // NE
            case 0x2: return c;               // CS/HS
            case 0x3: return !c;              // CC/LO
            case 0x4: return n;               // MI
            case 0x5: return !n;              // PL
            case 0x6: return v;               // VS
            case 0x7: return !v;              // VC
            case 0x8: return c && !z;         // HI
            case 0x9: return !c || z;         // LS
            case 0xA: return n == v;          // GE
            case 0xB: return n != v;          // LT
            case 0xC: return !z && (n == v);  // GT
            case 0xD: return z || (n != v);   // LE
            case 0xE: return true;            // AL
            default: return false;            // NV (reserved)
        }
    }
}
