package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * DSD over PCM (DoP 1.1): the raw DSD bit stream of a {@code .dsf} or {@code .dff} file packed into stereo 24-bit
 * PCM at a sixteenth of the DSD rate (DSD64: 176.4 kHz). Each 24-bit word holds a marker byte (alternating 0x05 /
 * 0xFA, fixed by the frame index so seeking is exact) and 16 DSD bits. The first two channels are used (mono is
 * duplicated). The exact length is {@code bytesPerChannel / 2} frames.
 */
final class DsdDopDecoder implements AudioDecoder {

    /** What the header of a DSD file says. */
    record Info(int channels, int dsdRate, long bytesPerChannel, long dataOffset, boolean lsbFirst, int blockSize,
            boolean interleavedBytes) {
        int pcmRate() {
            return dsdRate / 16;
        }

        long frames() {
            return bytesPerChannel / 2;
        }
    }

    private final Path file;
    private final Info info;
    private final RandomAccessFile raf;
    private long position;

    static DsdDopDecoder open(Path file) throws IOException {
        Info info = probe(file);
        if (info.frames() <= 0) {
            throw new IOException("no audio in " + file.getFileName());
        }
        return new DsdDopDecoder(file, info);
    }

    private DsdDopDecoder(Path file, Info info) throws IOException {
        this.file = file;
        this.info = info;
        this.raf = new RandomAccessFile(file.toFile(), "r");
    }

    /** Reads the header of a DSF or DFF file. */
    static Info probe(Path file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            byte[] magic = new byte[4];
            f.readFully(magic);
            String id = new String(magic, StandardCharsets.US_ASCII);
            if (id.equals("DSD ")) {
                return probeDsf(f, file);
            }
            if (id.equals("FRM8")) {
                return probeDff(f, file);
            }
            throw new IOException("not a DSD file: " + file.getFileName());
        }
    }

    private static Info probeDsf(RandomAccessFile f, Path file) throws IOException {
        f.seek(28);
        byte[] fmt = new byte[52];
        f.readFully(fmt);
        ByteBuffer b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
        if (!new String(fmt, 0, 4, StandardCharsets.US_ASCII).equals("fmt ")) {
            throw new IOException("DSF without fmt chunk: " + file.getFileName());
        }
        int channels = b.getInt(24);
        int rate = b.getInt(28);
        int bits = b.getInt(32);
        long samples = b.getLong(36);
        int block = b.getInt(44);
        byte[] data = new byte[12];
        f.readFully(data);
        if (!new String(data, 0, 4, StandardCharsets.US_ASCII).equals("data")) {
            throw new IOException("DSF without data chunk: " + file.getFileName());
        }
        if (channels < 1 || block <= 0) {
            throw new IOException("bad DSF header: " + file.getFileName());
        }
        return new Info(channels, rate, samples / 8, f.getFilePointer(), bits == 1, block, false);
    }

    private static Info probeDff(RandomAccessFile f, Path file) throws IOException {
        f.seek(12);
        byte[] form = new byte[4];
        f.readFully(form);
        int rate = 0;
        int channels = 0;
        long length = f.length();
        while (f.getFilePointer() + 12 <= length) {
            byte[] hdr = new byte[12];
            f.readFully(hdr);
            String id = new String(hdr, 0, 4, StandardCharsets.US_ASCII);
            long size = ByteBuffer.wrap(hdr, 4, 8).order(ByteOrder.BIG_ENDIAN).getLong();
            long body = f.getFilePointer();
            if (id.equals("PROP")) {
                byte[] snd = new byte[4];
                f.readFully(snd);
                long end = body + size;
                while (f.getFilePointer() + 12 <= end) {
                    byte[] ph = new byte[12];
                    f.readFully(ph);
                    String pid = new String(ph, 0, 4, StandardCharsets.US_ASCII);
                    long psize = ByteBuffer.wrap(ph, 4, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                    long pbody = f.getFilePointer();
                    if (pid.equals("FS  ")) {
                        rate = f.readInt();
                    } else if (pid.equals("CHNL")) {
                        channels = f.readUnsignedShort();
                    } else if (pid.equals("CMPR")) {
                        byte[] c = new byte[4];
                        f.readFully(c);
                        if (!new String(c, StandardCharsets.US_ASCII).equals("DSD ")) {
                            throw new IOException("DST-compressed DSD is not supported: " + file.getFileName());
                        }
                    }
                    f.seek(pbody + psize + (psize & 1));
                }
            } else if (id.equals("DSD ")) {
                if (rate <= 0 || channels <= 0) {
                    throw new IOException("DFF without properties: " + file.getFileName());
                }
                return new Info(channels, rate, size / channels, body, false, 0, true);
            }
            f.seek(body + size + (size & 1));
        }
        throw new IOException("DFF without sound data: " + file.getFileName());
    }

    @Override
    public int sampleRate() {
        return info.pcmRate();
    }

    @Override
    public int frameBytes() {
        return 6;
    }

    @Override
    public long totalSamples() {
        return info.frames();
    }

    @Override
    public void seek(long frame) {
        position = Math.max(0, Math.min(frame, info.frames()));
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        long left = info.frames() - position;
        if (left <= 0) {
            return -1;
        }
        int frames = (int) Math.min(len / 6, left);
        if (frames <= 0) {
            return 0;
        }
        int bytes = frames * 2;
        int chans = Math.min(2, info.channels());
        byte[][] ch = new byte[chans][bytes];
        fetch(position * 2, bytes, ch);
        for (int i = 0; i < frames; i++) {
            int marker = ((position + i) & 1) == 0 ? 0x05 : 0xFA;
            int p = off + i * 6;
            int l0 = bits(ch[0][i * 2]);
            int l1 = bits(ch[0][i * 2 + 1]);
            int r0 = chans > 1 ? bits(ch[1][i * 2]) : l0;
            int r1 = chans > 1 ? bits(ch[1][i * 2 + 1]) : l1;
            // little-endian 24-bit word: low byte first
            buf[p] = (byte) l1;
            buf[p + 1] = (byte) l0;
            buf[p + 2] = (byte) marker;
            buf[p + 3] = (byte) r1;
            buf[p + 4] = (byte) r0;
            buf[p + 5] = (byte) marker;
        }
        position += frames;
        return frames * 6;
    }

    private int bits(byte b) {
        int v = b & 0xff;
        return info.lsbFirst() ? Integer.reverse(v) >>> 24 : v;
    }

    /** Reads {@code count} bytes of every wanted channel starting at byte {@code first} of the channel. */
    private void fetch(long first, int count, byte[][] out) throws IOException {
        int chans = out.length;
        if (info.interleavedBytes()) {
            byte[] raw = new byte[count * info.channels()];
            raf.seek(info.dataOffset() + first * info.channels());
            raf.readFully(raw);
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < chans; c++) {
                    out[c][i] = raw[i * info.channels() + c];
                }
            }
            return;
        }
        int block = info.blockSize();
        int done = 0;
        while (done < count) {
            long idx = first + done;
            long blockNo = idx / block;
            int within = (int) (idx % block);
            int n = Math.min(count - done, block - within);
            for (int c = 0; c < chans; c++) {
                raf.seek(info.dataOffset() + (blockNo * info.channels() + c) * block + within);
                raf.readFully(out[c], done, n);
            }
            done += n;
        }
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    @Override
    public String toString() {
        return "DoP " + file.getFileName();
    }
}
