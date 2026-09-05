.PHONY: build install clean selftest

build:
	./gradlew assembleDebug

install:
	./gradlew installDebug

clean:
	./gradlew clean

# Runs the standalone CPU/decoder self-test with a plain JDK (no Android SDK
# needed) — compiles tools/selftest and executes it against the bundled
# test firmware, checking that DEBUG_MSG receives "NOK" and that a handful
# of ARM/Thumb data-processing, branch, and flag-update checks pass.
selftest:
	javac -d /tmp/selftest_out tools/selftest/com/nokiaos/emulator/*.java
	java -cp /tmp/selftest_out com.nokiaos.emulator.SelfTest tools/selftest/test_firmware.bin
