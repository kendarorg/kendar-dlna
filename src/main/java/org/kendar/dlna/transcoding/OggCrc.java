package org.kendar.dlna.transcoding;

/** Ogg page checksum (CRC-32, polynomial 0x04C11DB7, not reflected, initial value 0). */
public final class OggCrc {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            TABLE[i] = r;
        }
    }

    private OggCrc() {
    }

    public static int crc(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc = (crc << 8) ^ TABLE[((crc >>> 24) & 0xff) ^ (data[i] & 0xff)];
        }
        return crc;
    }
}
