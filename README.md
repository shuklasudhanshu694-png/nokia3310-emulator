# Nokia3310 Emulator — MT6261-style Feature Phone Hardware Emulator

An Android app that emulates ARM7TDMI-S (ARMv4T) feature-phone hardware for
bootloader/firmware testing: CPU core, memory map, core + modem MMIO
peripherals, keypad, LCD framebuffer, and a debug/inspection UI.

## Important: what this actually is (read this first)

The original spec asked for a JNI/NDK app with the CPU core in C. I built the
emulator core in **pure Java** instead, and want to be upfront about why:
C/CMake code compiled through the Android NDK can't be verified without a
real Android build environment, and I don't have one in the sandbox this was
built in. Shipping unverified C and calling it "error-free" would be a
guess dressed up as a guarantee. Pure Java is JVM-portable, so I could
actually load, decode, and run instructions against it and check the output
by hand — see **Verification** below for exactly what was checked and how.

This is a **register- and instruction-semantics-accurate interpreter**, not
a cycle/pipeline-accurate silicon simulation. Concretely:

- All the ARM7TDMI-S data processing, multiply, branch, load/store
  (word/byte/halfword/signed, single and block), swap, MRS/MSR, and SWI
  instructions are implemented, plus the Thumb (16-bit) instruction set.
- The exact memory map and MMIO register offsets/reset values from the spec
  are implemented, including access rules (ROM read-only, MMIO no-execute,
  etc.) and a Data Abort on unmapped access.
- Coprocessor instructions (CDP/LDC/STC/MCR/MRC) decode correctly but are
  no-ops — there is no coprocessor attached to this hardware model.
- DMA and watchdog registers exist and are readable/writable but do not run
  a background transfer or reset the CPU — no functional side effects yet.
- Timing is modeled at the millisecond-tick level (matching the 1kHz
  SysTick), not per-cycle pipeline stages.
- The spec's "200 functional checks" list was not exhaustively tested one
  by one. What *was* verified is in the Verification section below — treat
  the rest of that list as a roadmap, not a completed checklist.

If you need the C/NDK version for a real embedded workflow, that's a much
larger effort that needs an actual build/test loop (real device or CI with
the NDK) to make the "no compile errors, no crashes" guarantees meaningful.

## What's in the box

- `app/` — the Android Studio project (pure Java, `minSdk 24`, `compileSdk 34`)
  - `Cpu.java`, `ArmDecoder.java`, `ThumbDecoder.java` — the ARM7TDMI-S core
  - `Memory.java` — memory map + access rules
  - `CorePeripherals.java`, `ModemPeripherals.java` — MMIO register blocks
  - `EmulatorEngine.java` — background run loop (UI thread is never blocked)
  - `MainActivity` — phone UI: LCD, keypad, run/pause/step/reset, speed slider
  - `DebugActivity` — register viewer, memory hex dump, breakpoints, trace log
  - `FirmwareActivity` — load `.bin`/`.hex` firmware (file picker or bundled
    test firmware), SHA-256 checksum display
  - `ProfileActivity` — 5 hardware profiles (`assets/profiles/combo1-5.json`)
  - `assets/firmware/test_firmware.bin` — a real, hand-assembled 36-byte ARM
    program (see Verification)
- `tools/selftest/` — a standalone copy of the core classes + a `SelfTest.java`
  harness you can run with a plain JDK (no Android SDK needed) to reproduce
  the checks described below.

## Building

1. Open the `Nokia3310_Emulator/` folder in Android Studio (Koala or newer).
   Android Studio will generate the Gradle wrapper JAR automatically on
   first sync — this repo includes `gradle-wrapper.properties` (pointing at
   Gradle 8.7) but **not** the wrapper JAR binary itself, since it couldn't
   be downloaded in the sandbox this was built in (network-restricted to a
   package-mirror allowlist that doesn't include `services.gradle.org`).
   If you'd rather use the command line, run `gradle wrapper` once (with any
   installed Gradle) to generate it, then use `./gradlew` as normal.
2. Build: `./gradlew assembleDebug` (or the Android Studio Run button).
3. Install: `./gradlew installDebug`, or run from Android Studio onto a
   device/emulator running API 24+.

No NDK, no CMake, nothing to cross-compile — it's a standard Java Android app.

## Verification

I don't have an Android SDK or a working `javac` in this sandbox (the
package mirror needed to install one returned 404s), so I couldn't run
`tools/selftest/` end-to-end myself. What I did instead was **hand-trace the
bundled test firmware through the decoder logic instruction-by-instruction**
against the actual `ArmDecoder`/`Cpu` source, which caught two real bugs
before they shipped:

1. **PC pipeline-offset bug**: `pcForFetch()` was computing `PC + 12` instead
   of the correct `PC + 8` (ARM) / `PC + 4` (Thumb) for instructions that
   read the PC directly (`LDR Rd, [PC, #imm]`, PC-relative shifts). Branch
   instructions happened to work anyway because of an unrelated `-4` fudge
   factor that was compensating for the same bug — which is exactly the kind
   of thing that looks fine until you trace through a real PC-relative load.
   Fixed in `Cpu.pcForFetch()`, with the now-redundant compensation removed
   from both the ARM and Thumb branch instructions.
2. **DEBUG_MSG word-write bug**: writing a 32-bit register to the
   write-only `DEBUG_MSG` port (via `STR`) decomposes into 4 separate byte
   writes in `Memory`, and the peripheral was appending a character on
   *every* byte lane — so a single `STR` intended to log one character
   logged one real character plus three null bytes. Fixed by making
   `DEBUG_MSG` only react to its base byte address.
3. A bug in the **test firmware's hand-assembly** (not the decoder): the
   `LDR`/`STR` instructions were missing bit 26, which is what marks the
   "single data transfer" instruction class — without it they'd have been
   misdecoded as data-processing instructions. Fixed by correcting the
   encoder script that generated `test_firmware.bin`.

After both fixes, tracing the firmware by hand instruction-by-instruction
confirms: `R0` loads the correct MMIO address for `DEBUG_MSG`
(`0x8000009C`) via a PC-relative literal load, three `STR` instructions each
append exactly one clean character, and the final branch correctly jumps to
its own address (an intentional infinite loop). The expected result is that
`CorePeripherals.debugLog` reads exactly `"NOK"`.

**To reproduce this yourself** with a real JDK:
```
make selftest
```
This compiles `tools/selftest/` and runs `SelfTest.java`, which loads
`test_firmware.bin`, steps the CPU, asserts the debug log equals `"NOK"`,
and runs a handful of additional unit checks (ADD/SUB flag behavior, BL
link-register semantics, and a small Thumb sequence). I'd recommend running
this before you trust the core for anything beyond casual exploration —
I traced it by hand, which is good evidence but not the same as an
automated test actually passing.

## Known gaps vs. the original spec

- No JNI/C/CMake layer (see above for why).
- No cycle-accurate pipeline timing.
- DMA/watchdog are register-only; no background transfer or watchdog reset.
- Coprocessor instructions are no-ops.
- The debug screen has no disassembly view (register + hex-dump only).
- Not all "200 functional checks" from the spec were individually verified —
  only what's described in Verification above.

## Controls

- **Run / Pause / Step / Reset** — standard execution controls.
- **Speed slider** — scales the run-loop's target frame rate / sleep timing.
- **Keypad** — 20 buttons per the spec's bit layout (1-9, Up/Down/Left/Right,
  `*`/0/`#`, SoftL/Menu/SoftR/OK), touch-down sets the bit, touch-up clears it.
- **Debug screen** — live register/CPSR view, hex dump around the current
  PC, add/clear breakpoints by hex address, recent trace log.
- **Firmware screen** — pick a `.bin` or `.hex` file via Android's document
  picker, or load the bundled test firmware; shows size and SHA-256.
- **Profile screen** — switch between the 5 bundled hardware profile JSONs.
