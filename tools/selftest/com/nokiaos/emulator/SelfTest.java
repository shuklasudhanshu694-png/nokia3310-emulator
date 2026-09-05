package com.nokiaos.emulator;

import java.nio.file.Files;
import java.nio.file.Paths;

public class SelfTest {
    public static void main(String[] args) throws Exception {
        byte[] fw = Files.readAllBytes(Paths.get(args[0]));
        CorePeripherals core = new CorePeripherals();
        ModemPeripherals modem = new ModemPeripherals();
        Memory mem = new Memory(core, modem);
        mem.loadFirmware(fw);
        Cpu cpu = new Cpu(mem);

        for (int i = 0; i < 20; i++) {
            System.out.printf("step %d PC=%08X R0=%08X R1=%08X%n", i, cpu.r[15], cpu.r[0], cpu.r[1]);
            cpu.step();
        }
        System.out.println("DEBUG_MSG log: " + core.debugLog.toString());
        if (!core.debugLog.toString().equals("NOK")) {
            throw new RuntimeException("SELF TEST FAILED: expected 'NOK', got '" + core.debugLog + "'");
        }
        System.out.println("SELF TEST PASSED");

        // --- Additional unit checks ---
        testDataProcessing();
        testBranchAndLink();
        testThumb();
        System.out.println("ALL UNIT CHECKS PASSED");
    }

    static void testDataProcessing() {
        CorePeripherals core = new CorePeripherals();
        Memory mem = new Memory(core, new ModemPeripherals());
        Cpu cpu = new Cpu(mem);
        // MOV R0, #5 ; MOV R1, #3 ; ADD R2, R0, R1 (S) ; SUB R3, R0, R1 (S)
        int[] prog = {
            0xE3A00005, // MOV R0,#5
            0xE3A01003, // MOV R1,#3
            0xE0902001, // ADDS R2,R0,R1
            0xE0503001, // SUBS R3,R0,R1
        };
        writeProg(mem, prog);
        for (int i = 0; i < 4; i++) cpu.step();
        assertEq(cpu.r[0], 5, "R0");
        assertEq(cpu.r[1], 3, "R1");
        assertEq(cpu.r[2], 8, "R2 ADD");
        assertEq(cpu.r[3], 2, "R3 SUB");
        if (!cpu.flag(Cpu.BIT_C)) throw new RuntimeException("Expected carry set after SUBS with no borrow");
        System.out.println("testDataProcessing OK");
    }

    static void testBranchAndLink() {
        CorePeripherals core = new CorePeripherals();
        Memory mem = new Memory(core, new ModemPeripherals());
        Cpu cpu = new Cpu(mem);
        // At 0x0: BL +8 (to 0x10)  ; at 0x10: MOV R5,#42
        int[] prog = new int[8];
        prog[0] = 0xEB000001; // BL target (offset=1 word => target = pc+8+4 = 0x0+8+4=0xC... compute properly)
        // We'll just verify BL sets LR correctly and jumps somewhere sane; simpler direct test:
        writeProg(mem, new int[]{ 0xEB000000 }); // BL #0 -> offset0 => target = PC+8 = 0+8=8
        cpu.step();
        assertEq(cpu.r[15], 8, "PC after BL");
        assertEq(cpu.r[14], 4, "LR after BL");
        System.out.println("testBranchAndLink OK");
    }

    static void testThumb() {
        CorePeripherals core = new CorePeripherals();
        Memory mem = new Memory(core, new ModemPeripherals());
        Cpu cpu = new Cpu(mem);
        cpu.setFlag(Cpu.BIT_T, true);
        cpu.r[15] = 0;
        // Thumb: MOV R0,#7 (0x2007) ; MOV R1,#2 (0x2102) ; ADD R2,R0,R1 (0x1888? format2 reg) 
        short[] prog = { (short)0x2007, (short)0x2102, (short)0x1888 };
        for (int i = 0; i < prog.length; i++) {
            mem.write16(i * 2, prog[i] & 0xFFFF);
        }
        for (int i = 0; i < 3; i++) cpu.step();
        assertEq(cpu.r[0], 7, "thumb R0");
        assertEq(cpu.r[1], 2, "thumb R1");
        assertEq(cpu.r[2], 9, "thumb R2 = R0+R1");
        System.out.println("testThumb OK");
    }

    static void writeProg(Memory mem, int[] words) {
        for (int i = 0; i < words.length; i++) mem.write32(i * 4, words[i]);
    }

    static void assertEq(int actual, int expected, String label) {
        if (actual != expected) {
            throw new RuntimeException("FAIL " + label + ": expected " + expected + " got " + actual);
        }
    }
}
