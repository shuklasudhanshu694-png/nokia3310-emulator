package com.nokiaos.emulator;

/**
 * Decodes and executes 32-bit ARM (ARMv4T) instructions.
 * Covers: data processing, multiply/long-multiply, branch (B/BL/BX),
 * load/store (word/byte/halfword/signed, single and multiple), swap,
 * MRS/MSR, and SWI. CDP/LDC/STC/MCR/MRC (coprocessor) are decoded and
 * treated as no-ops with normal cycle cost, since this hardware model has
 * no coprocessor attached.
 */
public class ArmDecoder {

    public static int execute(Cpu cpu, int op) {
        int cond = (op >>> 28) & 0xF;
        if (!cpu.conditionPasses(cond)) return 1;

        // BX Rn : 0001 0010 1111 1111 1111 0001 Rn
        if ((op & 0x0FFFFFF0) == 0x012FFF10) {
            int rn = op & 0xF;
            int target = cpu.r[rn];
            boolean toThumb = (target & 1) != 0;
            cpu.setFlag(Cpu.BIT_T, toThumb);
            cpu.r[15] = target & ~1;
            return 3;
        }

        // Branch / Branch with Link: 101L
        if ((op & 0x0E000000) == 0x0A000000) {
            boolean link = ((op >>> 24) & 1) != 0;
            int offset = (op & 0x00FFFFFF) << 8 >> 8; // sign extend 24-bit
            offset <<= 2;
            if (link) cpu.r[14] = cpu.r[15] - 4 + 4; // address of next instruction (r15 already advanced by 4)
            cpu.r[15] = cpu.pcForFetch() + offset;
            return 3;
        }

        // SWI: 1111 imm24
        if ((op & 0x0F000000) == 0x0F000000) {
            cpu.raiseSwi();
            return 3;
        }

        // Multiply: 000000 A S Rd Rn Rs 1001 Rm
        if ((op & 0x0FC000F0) == 0x00000090) {
            return doMultiply(cpu, op);
        }
        // Multiply long: 00001 U A S RdHi RdLo Rs 1001 Rm
        if ((op & 0x0F8000F0) == 0x00800090) {
            return doMultiplyLong(cpu, op);
        }

        // Swap: 00010 B 00 Rn Rd 0000 1001 Rm
        if ((op & 0x0FB00FF0) == 0x01000090) {
            return doSwap(cpu, op);
        }

        // MRS/MSR: 00010 R 10 ...
        if ((op & 0x0FBF0FFF) == 0x010F0000) { // MRS
            int rd = (op >>> 12) & 0xF;
            boolean spsrSel = ((op >>> 22) & 1) != 0;
            cpu.r[rd] = spsrSel ? cpu.getSpsr() : cpu.cpsr;
            return 1;
        }
        if ((op & 0x0DB0F000) == 0x0120F000) { // MSR
            boolean spsrSel = ((op >>> 22) & 1) != 0;
            boolean immediate = ((op >>> 25) & 1) != 0;
            int val;
            if (immediate) {
                int imm = op & 0xFF;
                int rot = ((op >>> 8) & 0xF) * 2;
                val = Integer.rotateRight(imm, rot);
            } else {
                val = cpu.r[op & 0xF];
            }
            int fieldMask = (op >>> 16) & 0xF;
            int mask = 0;
            if ((fieldMask & 1) != 0) mask |= 0x000000FF; // control
            if ((fieldMask & 8) != 0) mask |= 0xFF000000; // flags
            if (spsrSel) {
                int cur = cpu.getSpsr();
                cpu.setSpsr((cur & ~mask) | (val & mask));
            } else {
                cpu.cpsr = (cpu.cpsr & ~mask) | (val & mask);
            }
            return 1;
        }

        // Load/Store multiple: 100P U S W L Rn reglist
        if ((op & 0x0E000000) == 0x08000000) {
            return doBlockTransfer(cpu, op);
        }

        // Single data transfer (LDR/STR word/byte, immediate or register offset): 01
        if ((op & 0x0C000000) == 0x04000000) {
            return doSingleTransfer(cpu, op);
        }

        // Halfword / signed transfers: 000P U I W L Rn Rd ... 1SH1 ...
        if ((op & 0x0E000090) == 0x00000090) {
            return doHalfwordTransfer(cpu, op);
        }

        // Coprocessor instructions - no-op (no coprocessor attached)
        if ((op & 0x0C000000) == 0x0C000000) {
            return 1;
        }

        // Data processing: 00
        if ((op & 0x0C000000) == 0x00000000) {
            return doDataProcessing(cpu, op);
        }

        return 1; // undefined -> treat as no-op (could raise undef exception)
    }

    private static int shiftOperand(Cpu cpu, int op, boolean updateCarry) {
        boolean immediate = ((op >>> 25) & 1) != 0;
        if (immediate) {
            int imm = op & 0xFF;
            int rot = ((op >>> 8) & 0xF) * 2;
            int val = Integer.rotateRight(imm, rot);
            if (updateCarry && rot != 0) cpu.setFlag(Cpu.BIT_C, ((val >>> 31) & 1) != 0);
            return val;
        } else {
            int rm = op & 0xF;
            int shiftType = (op >>> 5) & 0x3;
            boolean shiftByReg = ((op >>> 4) & 1) != 0;
            int amount;
            int base = (rm == 15) ? cpu.pcForFetch() : cpu.r[rm];
            if (shiftByReg) {
                int rs = (op >>> 8) & 0xF;
                amount = cpu.r[rs] & 0xFF;
            } else {
                amount = (op >>> 7) & 0x1F;
            }
            return applyShift(cpu, base, shiftType, amount, updateCarry, shiftByReg);
        }
    }

    private static int applyShift(Cpu cpu, int val, int type, int amount, boolean updateCarry, boolean byReg) {
        switch (type) {
            case 0: // LSL
                if (amount == 0) return val;
                if (amount >= 32) {
                    if (updateCarry) cpu.setFlag(Cpu.BIT_C, amount == 32 && (val & 1) != 0);
                    return 0;
                }
                if (updateCarry) cpu.setFlag(Cpu.BIT_C, ((val << (amount - 1)) & 0x80000000) != 0);
                return val << amount;
            case 1: // LSR
                if (amount == 0 && !byReg) amount = 32;
                if (amount == 0) return val;
                if (amount >= 32) {
                    if (updateCarry) cpu.setFlag(Cpu.BIT_C, amount == 32 && (val >>> 31) != 0);
                    return 0;
                }
                if (updateCarry) cpu.setFlag(Cpu.BIT_C, ((val >>> (amount - 1)) & 1) != 0);
                return val >>> amount;
            case 2: // ASR
                if (amount == 0 && !byReg) amount = 32;
                if (amount == 0) return val;
                if (amount >= 32) {
                    boolean bit = val < 0;
                    if (updateCarry) cpu.setFlag(Cpu.BIT_C, bit);
                    return bit ? -1 : 0;
                }
                if (updateCarry) cpu.setFlag(Cpu.BIT_C, ((val >> (amount - 1)) & 1) != 0);
                return val >> amount;
            case 3: // ROR / RRX
                if (amount == 0 && !byReg) {
                    boolean carryIn = cpu.flag(Cpu.BIT_C);
                    if (updateCarry) cpu.setFlag(Cpu.BIT_C, (val & 1) != 0);
                    return (val >>> 1) | (carryIn ? 0x80000000 : 0);
                }
                amount &= 0x1F;
                if (amount == 0) return val;
                if (updateCarry) cpu.setFlag(Cpu.BIT_C, ((val >>> (amount - 1)) & 1) != 0);
                return Integer.rotateRight(val, amount);
        }
        return val;
    }

    private static int doDataProcessing(Cpu cpu, int op) {
        int opcode = (op >>> 21) & 0xF;
        boolean s = ((op >>> 20) & 1) != 0;
        int rn = (op >>> 16) & 0xF;
        int rd = (op >>> 12) & 0xF;
        boolean carryAffecting = s && rd != 15;
        int operand2 = shiftOperand(cpu, op, carryAffecting);
        int opnd1 = (rn == 15) ? cpu.pcForFetch() : cpu.r[rn];

        long result = 0;
        boolean writesResult = true;
        boolean arithmetic = false;
        switch (opcode) {
            case 0x0: result = opnd1 & operand2; break; // AND
            case 0x1: result = opnd1 ^ operand2; break; // EOR
            case 0x2: result = (long) opnd1 - operand2; arithmetic = true; break; // SUB
            case 0x3: result = (long) operand2 - opnd1; arithmetic = true; break; // RSB
            case 0x4: result = (long) opnd1 + operand2; arithmetic = true; break; // ADD
            case 0x5: result = (long) opnd1 + operand2 + (cpu.flag(Cpu.BIT_C) ? 1 : 0); arithmetic = true; break; // ADC
            case 0x6: result = (long) opnd1 - operand2 - (cpu.flag(Cpu.BIT_C) ? 0 : 1); arithmetic = true; break; // SBC
            case 0x7: result = (long) operand2 - opnd1 - (cpu.flag(Cpu.BIT_C) ? 0 : 1); arithmetic = true; break; // RSC
            case 0x8: result = opnd1 & operand2; writesResult = false; break; // TST
            case 0x9: result = opnd1 ^ operand2; writesResult = false; break; // TEQ
            case 0xA: result = (long) opnd1 - operand2; arithmetic = true; writesResult = false; break; // CMP
            case 0xB: result = (long) opnd1 + operand2; arithmetic = true; writesResult = false; break; // CMN
            case 0xC: result = opnd1 | operand2; break; // ORR
            case 0xD: result = operand2; break; // MOV
            case 0xE: result = opnd1 & ~operand2; break; // BIC
            case 0xF: result = ~operand2; break; // MVN
        }

        int res32 = (int) result;
        if (writesResult) {
            cpu.r[rd] = res32;
            if (rd == 15) {
                if (s) cpu.cpsr = cpu.getSpsr();
            }
        }

        if (s && rd != 15) {
            cpu.setFlag(Cpu.BIT_Z, res32 == 0);
            cpu.setFlag(Cpu.BIT_N, res32 < 0);
            if (arithmetic) {
                if (opcode == 0x2 || opcode == 0xA) { // SUB/CMP
                    cpu.setFlag(Cpu.BIT_C, Integer.compareUnsigned(opnd1, operand2) >= 0);
                    cpu.setFlag(Cpu.BIT_V, ((opnd1 ^ operand2) & (opnd1 ^ res32)) < 0);
                } else if (opcode == 0x3) { // RSB
                    cpu.setFlag(Cpu.BIT_C, Integer.compareUnsigned(operand2, opnd1) >= 0);
                    cpu.setFlag(Cpu.BIT_V, ((operand2 ^ opnd1) & (operand2 ^ res32)) < 0);
                } else { // ADD/ADC/CMN family
                    cpu.setFlag(Cpu.BIT_C, ((result >>> 32) & 1) != 0);
                    cpu.setFlag(Cpu.BIT_V, (~(opnd1 ^ operand2) & (opnd1 ^ res32)) < 0);
                }
            }
        }
        return 1;
    }

    private static int doMultiply(Cpu cpu, int op) {
        int rd = (op >>> 16) & 0xF;
        int rn = (op >>> 12) & 0xF;
        int rs = (op >>> 8) & 0xF;
        int rm = op & 0xF;
        boolean acc = ((op >>> 21) & 1) != 0;
        boolean s = ((op >>> 20) & 1) != 0;
        int result = cpu.r[rm] * cpu.r[rs] + (acc ? cpu.r[rn] : 0);
        cpu.r[rd] = result;
        if (s) {
            cpu.setFlag(Cpu.BIT_Z, result == 0);
            cpu.setFlag(Cpu.BIT_N, result < 0);
        }
        return 2;
    }

    private static int doMultiplyLong(Cpu cpu, int op) {
        int rdHi = (op >>> 16) & 0xF;
        int rdLo = (op >>> 12) & 0xF;
        int rs = (op >>> 8) & 0xF;
        int rm = op & 0xF;
        boolean signedMul = ((op >>> 22) & 1) != 0;
        boolean acc = ((op >>> 21) & 1) != 0;
        boolean s = ((op >>> 20) & 1) != 0;
        long product;
        if (signedMul) {
            product = (long) cpu.r[rm] * (long) cpu.r[rs];
        } else {
            product = (cpu.r[rm] & 0xFFFFFFFFL) * (cpu.r[rs] & 0xFFFFFFFFL);
        }
        if (acc) {
            long accVal = ((long) cpu.r[rdHi] << 32) | (cpu.r[rdLo] & 0xFFFFFFFFL);
            product += accVal;
        }
        cpu.r[rdLo] = (int) product;
        cpu.r[rdHi] = (int) (product >>> 32);
        if (s) {
            cpu.setFlag(Cpu.BIT_Z, product == 0);
            cpu.setFlag(Cpu.BIT_N, product < 0);
        }
        return 3;
    }

    private static int doSwap(Cpu cpu, int op) {
        int rn = (op >>> 16) & 0xF;
        int rd = (op >>> 12) & 0xF;
        int rm = op & 0xF;
        boolean byteSwap = ((op >>> 22) & 1) != 0;
        int addr = cpu.r[rn];
        if (byteSwap) {
            int mem = cpu.mem.read8(addr);
            cpu.mem.write8(addr, cpu.r[rm]);
            cpu.r[rd] = mem;
        } else {
            int mem = cpu.mem.read32(addr);
            cpu.mem.write32(addr, cpu.r[rm]);
            cpu.r[rd] = mem;
        }
        return 4;
    }

    private static int doSingleTransfer(Cpu cpu, int op) {
        boolean immediate = ((op >>> 25) & 1) == 0; // note: bit25=0 means immediate offset here
        boolean pre = ((op >>> 24) & 1) != 0;
        boolean up = ((op >>> 23) & 1) != 0;
        boolean byteXfer = ((op >>> 22) & 1) != 0;
        boolean writeBack = ((op >>> 21) & 1) != 0;
        boolean load = ((op >>> 20) & 1) != 0;
        int rn = (op >>> 16) & 0xF;
        int rd = (op >>> 12) & 0xF;

        int offset;
        if (immediate) {
            offset = op & 0xFFF;
        } else {
            int rm = op & 0xF;
            int shiftType = (op >>> 5) & 0x3;
            int amount = (op >>> 7) & 0x1F;
            offset = applyShift(cpu, cpu.r[rm], shiftType, amount, false, false);
        }
        if (!up) offset = -offset;

        int base = (rn == 15) ? cpu.pcForFetch() : cpu.r[rn];
        int addr = pre ? base + offset : base;

        if (load) {
            int val = byteXfer ? cpu.mem.read8(addr) : cpu.mem.read32(addr);
            cpu.r[rd] = val;
        } else {
            int val = (rd == 15) ? cpu.pcForFetch() : cpu.r[rd];
            if (byteXfer) cpu.mem.write8(addr, val); else cpu.mem.write32(addr, val);
        }

        if (!pre) addr = base + offset;
        if ((writeBack || !pre) && rn != 15) cpu.r[rn] = addr;

        return load ? 3 : 2;
    }

    private static int doHalfwordTransfer(Cpu cpu, int op) {
        boolean pre = ((op >>> 24) & 1) != 0;
        boolean up = ((op >>> 23) & 1) != 0;
        boolean immediate = ((op >>> 22) & 1) != 0;
        boolean writeBack = ((op >>> 21) & 1) != 0;
        boolean load = ((op >>> 20) & 1) != 0;
        int rn = (op >>> 16) & 0xF;
        int rd = (op >>> 12) & 0xF;
        int sh = (op >>> 5) & 0x3; // 01=H 10=SB 11=SH

        int offset;
        if (immediate) {
            offset = ((op >>> 4) & 0xF0) | (op & 0xF);
        } else {
            offset = cpu.r[op & 0xF];
        }
        if (!up) offset = -offset;

        int base = cpu.r[rn];
        int addr = pre ? base + offset : base;

        if (load) {
            int val;
            switch (sh) {
                case 1: val = cpu.mem.read16(addr) & 0xFFFF; break; // H
                case 2: val = (byte) cpu.mem.read8(addr); break;    // SB
                case 3: val = (short) cpu.mem.read16(addr); break;  // SH
                default: val = 0;
            }
            cpu.r[rd] = val;
        } else {
            cpu.mem.write16(addr, cpu.r[rd]);
        }

        if (!pre) addr = base + offset;
        if ((writeBack || !pre) && rn != 15) cpu.r[rn] = addr;

        return load ? 3 : 2;
    }

    private static int doBlockTransfer(Cpu cpu, int op) {
        boolean pre = ((op >>> 24) & 1) != 0;
        boolean up = ((op >>> 23) & 1) != 0;
        boolean sBit = ((op >>> 22) & 1) != 0;
        boolean writeBack = ((op >>> 21) & 1) != 0;
        boolean load = ((op >>> 20) & 1) != 0;
        int rn = (op >>> 16) & 0xF;
        int list = op & 0xFFFF;

        int addr = cpu.r[rn];
        int count = Integer.bitCount(list);
        int startAddr = up ? addr : addr - count * 4;
        int cursor = startAddr + (pre == up ? 4 : 0);

        for (int i = 0; i < 16; i++) {
            if ((list & (1 << i)) == 0) continue;
            if (load) {
                cpu.r[i] = cpu.mem.read32(cursor);
            } else {
                cpu.mem.write32(cursor, cpu.r[i]);
            }
            cursor += 4;
        }

        if (writeBack) {
            cpu.r[rn] = up ? startAddr + count * 4 : startAddr;
        }
        if (sBit && load && (list & 0x8000) != 0) {
            cpu.cpsr = cpu.getSpsr();
        }
        return 2 + count;
    }
}
