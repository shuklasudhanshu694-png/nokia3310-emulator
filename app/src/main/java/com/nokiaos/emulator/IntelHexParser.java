package com.nokiaos.emulator;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Parses an Intel HEX file into a flat binary image starting at address 0. */
public class IntelHexParser {

    public static byte[] parse(byte[] hexFileBytes) {
        String text = new String(hexFileBytes, StandardCharsets.US_ASCII);
        String[] lines = text.split("\\r?\\n");
        java.util.TreeMap<Integer, Byte> image = new java.util.TreeMap<>();
        int upperAddr = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) != ':') continue;
            int byteCount = Integer.parseInt(line.substring(1, 3), 16);
            int addr = Integer.parseInt(line.substring(3, 7), 16);
            int type = Integer.parseInt(line.substring(7, 9), 16);
            int dataStart = 9;

            if (type == 0x00) { // data
                for (int i = 0; i < byteCount; i++) {
                    int b = Integer.parseInt(line.substring(dataStart + i * 2, dataStart + i * 2 + 2), 16);
                    image.put((upperAddr << 16) + addr + i, (byte) b);
                }
            } else if (type == 0x04) { // extended linear address
                upperAddr = Integer.parseInt(line.substring(dataStart, dataStart + 4), 16);
            } else if (type == 0x01) { // EOF
                break;
            }
        }

        if (image.isEmpty()) return new byte[0];
        int max = image.lastKey();
        byte[] out = new byte[max + 1];
        for (java.util.Map.Entry<Integer, Byte> e : image.entrySet()) {
            out[e.getKey()] = e.getValue();
        }
        return out;
    }
}
