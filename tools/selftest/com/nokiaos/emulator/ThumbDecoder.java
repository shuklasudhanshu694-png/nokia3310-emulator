package com.nokiaos.emulator;

/**
 * Decodes and executes 16-bit Thumb instructions (ARMv4T subset):
 * MOV/CMP/ADD/SUB immediate, ALU ops, hi-register ops incl. BX,
 * PC-relative load, load/store register & immediate offset, load/store
 * halfword, SP-relative load/store, load address, add offset to SP,
 * PUSH/POP, load/store multiple, conditional branch, SWI, unconditional
 * branch, and long branch with link (BL).
 */
public class ThumbDecoder {

    public static int execute(Cpu cpu, int op) {
        int top5 = (op >>> 11) & 0x1F;

        // Format 1: move shifted register (000 op Rs Rd) -- top5 = 000xx
        if ((op & 0xE000) == 0x0000 && (op & 0x1800) != 0x1800) {
            int shType = (op >>> 11) & 0x3;
            int amount = (op >>> 6) & 0x1F;
            int rs = (op >>> 3) & 0x7;
            int rd = op & 0x7;
            int val = shiftImm(cpu, cpu.r[rs], shType, amount);
            cpu.r[rd] = val;
            setNZ(cpu, val);
            return 1;
        }

        // Format 2: add/subtract (00011 I op Rn/imm Rs Rd)
        if ((op & 0xF800) == 0x1800) {
            boolean immediate = ((op >>> 10) & 1) != 0;
            boolean sub = ((op >>> 9) & 1) != 0;
            int rn = (op >>> 6) & 0x7;
            int rs = (op >>> 3) & 0x7;
            int rd = op & 0x7;
            int operand = immediate ? rn : cpu.r[rn];
            addSub(cpu, rd, cpu.r[rs], operand, sub, true);
            return 1;
        }

        // Format 3: move/compare/add/subtract immediate (001 op Rd imm8)
        if ((op & 0xE000) == 0x2000) {
            int subop = (op >>> 11) & 0x3;
            int rd = (op >>> 8) & 0x7;
            int imm = op & 0xFF;
            switch (subop) {
                case 0: cpu.r[rd] = imm; setNZ(cpu, imm); break; // MOV
                case 1: addSub(cpu, -1, cpu.r[rd], imm, true, true); break; // CMP
                case 2: addSub(cpu, rd, cpu.r[rd], imm, false, true); break; // ADD
                case 3: addSub(cpu, rd, cpu.r[rd], imm, true, true); break; // SUB
            }
            return 1;
        }

        // Format 4: ALU operations (010000 op Rs Rd)
        if ((op & 0xFC00) == 0x4000) {
            int alu = (op >>> 6) & 0xF;
            int rs = (op >>> 3) & 0x7;
            int rd = op & 0x7;
            doAlu(cpu, alu, rd, rs);
            return 1;
        }

        // Format 5: hi register operations / BX (010001 op H1 H2 Rs/Hs Rd/Hd)
        if ((op & 0xFC00) == 0x4400) {
            int opc = (op >>> 8) & 0x3;
            boolean h1 = ((op >>> 7) & 1) != 0;
            boolean h2 = ((op >>> 6) & 1) != 0;
            int rs = ((op >>> 3) & 0x7) + (h2 ? 8 : 0);
            int rd = (op & 0x7) + (h1 ? 8 : 0);
            switch (opc) {
                case 0: cpu.r[rd] = cpu.r[rd] + cpu.r[rs]; if (rd == 15) cpu.r[15] &= ~1; break; // ADD
                case 1: addSub(cpu, -1, cpu.r[rd], cpu.r[rs], true, true); break; // CMP
                case 2: cpu.r[rd] = cpu.r[rs]; if (rd == 15) cpu.r[15] &= ~1; break; // MOV
                case 3: // BX
                    boolean toThumb = (cpu.r[rs] & 1) != 0;
                    cpu.setFlag(Cpu.BIT_T, toThumb);
                    cpu.r[15] = cpu.r[rs] & ~1;
                    break;
            }
            return 2;
        }

        // Format 6: PC-relative load (01001 Rd imm8)
        if ((op & 0xF800) == 0x4800) {
            int rd = (op >>> 8) & 0x7;
            int imm = (op & 0xFF) << 2;
            int base = (cpu.pcForFetch()) & ~3;
            cpu.r[rd] = cpu.mem.read32(base + imm);
            return 3;
        }

        // Format 7: load/store with register offset (0101 L B 0 Ro Rb Rd)
        if ((op & 0xF200) == 0x5000) {
            boolean load = ((op >>> 11) & 1) != 0;
            boolean byteXfer = ((op >>> 10) & 1) != 0;
            int ro = (op >>> 6) & 0x7;
            int rb = (op >>> 3) & 0x7;
            int rd = op & 0x7;
            int addr = cpu.r[rb] + cpu.r[ro];
            if (load) cpu.r[rd] = byteXfer ? (cpu.mem.read8(addr) & 0xFF) : cpu.mem.read32(addr);
            else if (byteXfer) cpu.mem.write8(addr, cpu.r[rd]); else cpu.mem.write32(addr, cpu.r[rd]);
            return load ? 3 : 2;
        }

        // Format 8: load/store sign-extended byte/halfword (0101 H S 1 Ro Rb Rd)
        if ((op & 0xF200) == 0x5200) {
            boolean hFlag = ((op >>> 11) & 1) != 0;
            boolean sFlag = ((op >>> 10) & 1) != 0;
            int ro = (op >>> 6) & 0x7;
            int rb = (op >>> 3) & 0x7;
            int rd = op & 0x7;
            int addr = cpu.r[rb] + cpu.r[ro];
            if (!sFlag && !hFlag) cpu.mem.write16(addr, cpu.r[rd]); // STRH
            else if (!sFlag) cpu.r[rd] = cpu.mem.read16(addr) & 0xFFFF; // LDRH
            else if (!hFlag) cpu.r[rd] = (byte) cpu.mem.read8(addr); // LDSB
            else cpu.r[rd] = (short) cpu.mem.read16(addr); // LDSH
            return 3;
        }

        // Format 9: load/store with immediate offset (011 B L imm5 Rb Rd)
        if ((op & 0xE000) == 0x6000) {
            boolean byteXfer = ((op >>> 12) & 1) != 0;
            boolean load = ((op >>> 11) & 1) != 0;
            int imm5 = (op >>> 6) & 0x1F;
            int rb = (op >>> 3) & 0x7;
            int rd = op & 0x7;
            int addr = cpu.r[rb] + (byteXfer ? imm5 : (imm5 << 2));
            if (load) cpu.r[rd] = byteXfer ? (cpu.mem.read8(addr) & 0xFF) : cpu.mem.read32(addr);
            else if (byteXfer) cpu.mem.write8(addr, cpu.r[rd]); else cpu.mem.write32(addr, cpu.r[rd]);
            return load ? 3 : 2;
        }

        // Format 10: load/store halfword (1000 L imm5 Rb Rd)
        if ((op & 0xF000) == 0x8000) {
            boolean load = ((op >>> 11) & 1) != 0;
            int imm5 = (op >>> 6) & 0x1F;
            int rb = (op >>> 3) & 0x7;
            int rd = op & 0x7;
            int addr = cpu.r[rb] + (imm5 << 1);
            if (load) cpu.r[rd] = cpu.mem.read16(addr) & 0xFFFF;
            else cpu.mem.write16(addr, cpu.r[rd]);
            return load ? 3 : 2;
        }

        // Format 11: SP-relative load/store (1001 L Rd imm8)
        if ((op & 0xF000) == 0x9000) {
            boolean load = ((op >>> 11) & 1) != 0;
            int rd = (op >>> 8) & 0x7;
            int imm = (op & 0xFF) << 2;
            int addr = cpu.r[13] + imm;
            if (load) cpu.r[rd] = cpu.mem.read32(addr);
            else cpu.mem.write32(addr, cpu.r[rd]);
            return load ? 3 : 2;
        }

        // Format 12: load address (1010 SP Rd imm8)
        if ((op & 0xF000) == 0xA000) {
            boolean useSp = ((op >>> 11) & 1) != 0;
            int rd = (op >>> 8) & 0x7;
            int imm = (op & 0xFF) << 2;
            int base = useSp ? cpu.r[13] : (cpu.pcForFetch() & ~3);
            cpu.r[rd] = base + imm;
            return 1;
        }

        // Format 13: add offset to SP (10110000 S imm7)
        if ((op & 0xFF00) == 0xB000) {
            boolean neg = ((op >>> 7) & 1) != 0;
            int imm = (op & 0x7F) << 2;
            cpu.r[13] += neg ? -imm : imm;
            return 1;
        }

        // Format 14: push/pop registers (1011 L 10 R reglist)
        if ((op & 0xF600) == 0xB400) {
            boolean load = ((op >>> 11) & 1) != 0;
            boolean rBit = ((op >>> 8) & 1) != 0;
            int list = op & 0xFF;
            int sp = cpu.r[13];
            if (load) { // POP
                for (int i = 0; i < 8; i++) {
                    if ((list & (1 << i)) != 0) { cpu.r[i] = cpu.mem.read32(sp); sp += 4; }
                }
                if (rBit) { cpu.r[15] = cpu.mem.read32(sp) & ~1; sp += 4; }
            } else { // PUSH
                int count = Integer.bitCount(list) + (rBit ? 1 : 0);
                sp -= count * 4;
                int cursor = sp;
                for (int i = 0; i < 8; i++) {
                    if ((list & (1 << i)) != 0) { cpu.mem.write32(cursor, cpu.r[i]); cursor += 4; }
                }
                if (rBit) cpu.mem.write32(cursor, cpu.r[14]);
            }
            cpu.r[13] = sp;
            return 4;
        }

        // Format 15: multiple load/store (1100 L Rb reglist)
        if ((op & 0xF000) == 0xC000) {
            boolean load = ((op >>> 11) & 1) != 0;
            int rb = (op >>> 8) & 0x7;
            int list = op & 0xFF;
            int addr = cpu.r[rb];
            for (int i = 0; i < 8; i++) {
                if ((list & (1 << i)) != 0) {
                    if (load) cpu.r[i] = cpu.mem.read32(addr); else cpu.mem.write32(addr, cpu.r[i]);
                    addr += 4;
                }
            }
            cpu.r[rb] = addr;
            return 3;
        }

        // Format 17: software interrupt (11011111 imm8)
        if ((op & 0xFF00) == 0xDF00) {
            cpu.raiseSwi();
            return 3;
        }

        // Format 16: conditional branch (1101 cond imm8)
        if ((op & 0xF000) == 0xD000) {
            int cond = (op >>> 8) & 0xF;
            if (cpu.conditionPasses(cond)) {
                int offset = ((op & 0xFF) << 24 >> 24) << 1; // sign extend 8-bit, *2
                cpu.r[15] = cpu.pcForFetch() + offset;
            }
            return 3;
        }

        // Format 18: unconditional branch (11100 imm11)
        if ((op & 0xF800) == 0xE000) {
            int offset = ((op & 0x7FF) << 21 >> 21) << 1;
            cpu.r[15] = cpu.pcForFetch() + offset;
            return 3;
        }

        // Format 19: long branch with link (1111 H imm11)
        if ((op & 0xF000) == 0xF000) {
            boolean high = ((op >>> 11) & 1) == 0;
            int imm11 = op & 0x7FF;
            if (high) {
                int offset = (imm11 << 21) >> 9; // sign-extended, shifted for upper half
                cpu.r[14] = cpu.pcForFetch() + offset;
            } else {
                int next = cpu.r[15];
                cpu.r[15] = cpu.r[14] + (imm11 << 1);
                cpu.r[14] = next | 1;
            }
            return 3;
        }

        return 1; // undefined -> no-op
    }

    private static int shiftImm(Cpu cpu, int val, int type, int amount) {
        switch (type) {
            case 0: // LSL
                if (amount == 0) return val;
                cpu.setFlag(Cpu.BIT_C, amount <= 32 && ((val << (amount - 1)) & 0x80000000) != 0);
                return amount >= 32 ? 0 : val << amount;
            case 1: // LSR
                if (amount == 0) amount = 32;
                cpu.setFlag(Cpu.BIT_C, amount <= 32 && ((val >>> (amount - 1)) & 1) != 0);
                return amount >= 32 ? 0 : val >>> amount;
            case 2: // ASR
                if (amount == 0) amount = 32;
                if (amount >= 32) { boolean neg = val < 0; cpu.setFlag(Cpu.BIT_C, neg); return neg ? -1 : 0; }
                cpu.setFlag(Cpu.BIT_C, ((val >> (amount - 1)) & 1) != 0);
                return val >> amount;
        }
        return val;
    }

    private static void setNZ(Cpu cpu, int val) {
        cpu.setFlag(Cpu.BIT_Z, val == 0);
        cpu.setFlag(Cpu.BIT_N, val < 0);
    }

    private static void addSub(Cpu cpu, int rd, int a, int b, boolean sub, boolean setFlags) {
        long result = sub ? ((long) a - b) : ((long) a + b);
        int res32 = (int) result;
        if (rd >= 0) cpu.r[rd] = res32;
        if (setFlags) {
            cpu.setFlag(Cpu.BIT_Z, res32 == 0);
            cpu.setFlag(Cpu.BIT_N, res32 < 0);
            if (sub) {
                cpu.setFlag(Cpu.BIT_C, Integer.compareUnsigned(a, b) >= 0);
                cpu.setFlag(Cpu.BIT_V, ((a ^ b) & (a ^ res32)) < 0);
            } else {
                cpu.setFlag(Cpu.BIT_C, ((result >>> 32) & 1) != 0);
                cpu.setFlag(Cpu.BIT_V, (~(a ^ b) & (a ^ res32)) < 0);
            }
        }
    }

    private static void doAlu(Cpu cpu, int op, int rd, int rs) {
        int a = cpu.r[rd], b = cpu.r[rs];
        int result;
        switch (op) {
            case 0x0: result = a & b; cpu.r[rd] = result; setNZ(cpu, result); break; // AND
            case 0x1: result = a ^ b; cpu.r[rd] = result; setNZ(cpu, result); break; // EOR
            case 0x2: result = a << (b & 0xFF); cpu.r[rd] = result; setNZ(cpu, result); break; // LSL
            case 0x3: result = (b & 0xFF) >= 32 ? 0 : a >>> (b & 0xFF); cpu.r[rd] = result; setNZ(cpu, result); break; // LSR
            case 0x4: result = a >> Math.min(b & 0xFF, 31); cpu.r[rd] = result; setNZ(cpu, result); break; // ASR
            case 0x5: addSub(cpu, rd, a, b + (cpu.flag(Cpu.BIT_C) ? 1 : 0), false, true); break; // ADC (approx)
            case 0x6: addSub(cpu, rd, a, b + (cpu.flag(Cpu.BIT_C) ? 0 : 1), true, true); break; // SBC (approx)
            case 0x7: result = Integer.rotateRight(a, b & 0x1F); cpu.r[rd] = result; setNZ(cpu, result); break; // ROR
            case 0x8: result = a & b; setNZ(cpu, result); break; // TST
            case 0x9: addSub(cpu, rd, 0, b, true, true); break; // NEG
            case 0xA: addSub(cpu, -1, a, b, true, true); break; // CMP
            case 0xB: addSub(cpu, -1, a, b, false, true); break; // CMN
            case 0xC: result = a | b; cpu.r[rd] = result; setNZ(cpu, result); break; // ORR
            case 0xD: cpu.r[rd] = a * b; setNZ(cpu, cpu.r[rd]); break; // MUL
            case 0xE: result = a & ~b; cpu.r[rd] = result; setNZ(cpu, result); break; // BIC
            case 0xF: result = ~b; cpu.r[rd] = result; setNZ(cpu, result); break; // MVN
        }
    }
}
