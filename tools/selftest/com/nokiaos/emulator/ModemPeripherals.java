package com.nokiaos.emulator;

/**
 * Modem MMIO block at 0xA0000000.
 * 0x000 MODEM_CMD (write AT command, one char at a time or via bulk write from CPU code)
 * 0x004 MODEM_RESP (read response, one char at a time)
 * 0x008 MODEM_STATUS bit0=TX ready bit1=RX ready bit2=Registered
 * 0x00C MODEM_SIGNAL (0-31)
 */
public class ModemPeripherals {

    public static final int MODEM_CMD = 0x000;
    public static final int MODEM_RESP = 0x004;
    public static final int MODEM_STATUS = 0x008;
    public static final int MODEM_SIGNAL = 0x00C;

    private final StringBuilder cmdBuf = new StringBuilder();
    private String respBuf = "";
    private int respPos = 0;
    private int status = 0x1; // TX ready
    private int signal = 22;
    private boolean registered = true;

    public void reset() {
        cmdBuf.setLength(0);
        respBuf = "";
        respPos = 0;
        status = 0x1;
        signal = 22;
        registered = true;
    }

    public int readByte(int off) {
        switch (off & ~3) {
            case MODEM_RESP:
                if (respPos < respBuf.length()) {
                    return respBuf.charAt(respPos++);
                }
                return 0;
            case MODEM_STATUS:
                return status | (registered ? 0x4 : 0) | ((respPos < respBuf.length()) ? 0x2 : 0);
            case MODEM_SIGNAL:
                return signal;
            default:
                return 0;
        }
    }

    public void writeByte(int off, int val) {
        if ((off & ~3) == MODEM_CMD) {
            char c = (char) (val & 0xFF);
            if (c == '\r' || c == '\n') {
                if (cmdBuf.length() > 0) {
                    handleCommand(cmdBuf.toString().trim());
                    cmdBuf.setLength(0);
                }
            } else {
                cmdBuf.append(c);
            }
        }
    }

    private void handleCommand(String cmd) {
        String upper = cmd.toUpperCase();
        if (upper.equals("AT")) respBuf = "OK\r\n";
        else if (upper.equals("AT+CGMI")) respBuf = "GENERIC\r\nOK\r\n";
        else if (upper.equals("AT+CGMM")) respBuf = "MT6261\r\nOK\r\n";
        else if (upper.equals("AT+CGSN")) respBuf = "000000000000000\r\nOK\r\n";
        else if (upper.equals("AT+CREG?")) respBuf = "+CREG: 0," + (registered ? "1" : "0") + "\r\nOK\r\n";
        else if (upper.equals("AT+CSQ")) respBuf = "+CSQ: " + signal + ",99\r\nOK\r\n";
        else if (upper.startsWith("ATD")) respBuf = "OK\r\n";
        else if (upper.equals("ATA")) respBuf = "OK\r\n";
        else if (upper.equals("ATH")) respBuf = "OK\r\n";
        else if (upper.startsWith("AT+CMGS")) respBuf = "> \r\n";
        else if (upper.startsWith("AT+CMGR")) respBuf = "+CMGR: \"REC UNREAD\"\r\nOK\r\n";
        else if (upper.startsWith("AT+CMGD")) respBuf = "OK\r\n";
        else if (upper.startsWith("AT+CPBR")) respBuf = "OK\r\n";
        else if (upper.startsWith("AT+CPBW")) respBuf = "OK\r\n";
        else if (upper.startsWith("AT+COPS")) respBuf = "+COPS: 0,0,\"EMULATOR\"\r\nOK\r\n";
        else if (upper.startsWith("AT+CLIP")) respBuf = "OK\r\n";
        else respBuf = "ERROR\r\n";
        respPos = 0;
    }
}
