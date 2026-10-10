package org.kendar.dlna.transcoding;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Ogg Vorbis through the JOrbis packet-level API with our own Ogg demux. The exact length is the final page granule.
 * Seeking restarts at a page boundary: the last packet of the preceding page is fed first (it supplies the left
 * overlap half), so the output after it is the continuous decode from that page's granule position.
 */
final class VorbisDecoder extends FixedLengthDecoder {

    private final OggFile ogg;
    private final List<OggFile.Page> pages;
    private final byte[][] headers = new byte[3][];
    private final int firstAudioPage;
    private final int serial;
    private final int rate;
    private final int channels;

    private Info info;
    private Comment comment;
    private DspState dsp;
    private Block block;
    private OggFile.Assembler assembler;
    private int nextPage;
    private final Deque<byte[]> packets = new ArrayDeque<>();
    private long packetNo;
    private byte[] pending = new byte[0];
    private int pendingPos;
    private long discard;
    private final float[][][] pcmOut = new float[1][][];
    private final int[] index = new int[16];

    static VorbisDecoder open(Path file) throws IOException {
        OggFile ogg = new OggFile(file);
        try {
            return new VorbisDecoder(ogg);
        } catch (IOException | RuntimeException e) {
            ogg.close();
            throw e;
        }
    }

    private VorbisDecoder(OggFile ogg) throws IOException {
        super(lastGranule(ogg));
        this.ogg = ogg;
        this.pages = ogg.pages();
        this.serial = pages.get(0).serial();
        OggFile.Assembler asm = new OggFile.Assembler();
        int got = 0;
        int i = 0;
        while (got < 3 && i < pages.size()) {
            OggFile.Page p = pages.get(i++);
            if (p.serial() != serial) {
                continue;
            }
            for (byte[] pkt : asm.feed(p.continued(), ogg.lacing(p), ogg.body(p))) {
                if (got < 3) {
                    headers[got++] = pkt;
                }
            }
        }
        if (got < 3) {
            throw new IOException("Not an Ogg Vorbis stream: headers incomplete");
        }
        firstAudioPage = i;
        initCodec();
        rate = info.rate;
        channels = info.channels;
        if (rate < 1 || channels < 1) {
            throw new IOException("Invalid Vorbis stream parameters");
        }
        restart(firstAudioPage, null);
    }

    private static long lastGranule(OggFile ogg) throws IOException {
        List<OggFile.Page> pages = ogg.pages();
        for (int i = pages.size() - 1; i >= 0; i--) {
            if (pages.get(i).granule() > 0) {
                return pages.get(i).granule();
            }
        }
        throw new IOException("Ogg stream has no granule positions");
    }

    private void initCodec() throws IOException {
        info = new Info();
        comment = new Comment();
        info.init();
        comment.init();
        for (int i = 0; i < 3; i++) {
            Packet op = packet(headers[i]);
            op.b_o_s = i == 0 ? 1 : 0;
            if (info.synthesis_headerin(comment, op) < 0) {
                throw new IOException("Not an Ogg Vorbis stream: bad header " + i);
            }
        }
        dsp = new DspState();
        dsp.synthesis_init(info);
        block = new Block(dsp);
        block.init(dsp);
    }

    private Packet packet(byte[] data) {
        Packet op = new Packet();
        op.packet_base = data;
        op.packet = 0;
        op.bytes = data.length;
        op.granulepos = -1;
        op.packetno = packetNo++;
        return op;
    }

    /** Fresh codec state; decoding resumes with {@code first} (may be null) then the packets of page {@code from}. */
    private void restart(int from, byte[] first) throws IOException {
        initCodec();
        assembler = new OggFile.Assembler();
        packets.clear();
        pending = new byte[0];
        pendingPos = 0;
        discard = 0;
        nextPage = from;
        if (first != null) {
            feed(first);
        }
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    protected void doSeek(long frame) throws IOException {
        int best = -1;
        for (int i = firstAudioPage; i < pages.size(); i++) {
            OggFile.Page p = pages.get(i);
            if (p.granule() < 0 || p.continued() || p.serial() != serial) {
                continue;
            }
            boolean nextFresh = i + 1 >= pages.size() || !pages.get(i + 1).continued();
            if (!nextFresh) {
                continue;
            }
            if (p.granule() <= frame) {
                best = i;
            } else {
                break;
            }
        }
        List<byte[]> pk = best < 0 ? List.of() : ogg.packets(pages.get(best));
        if (pk.isEmpty()) {
            restart(firstAudioPage, null);
            discard = frame;
            return;
        }
        restart(best + 1, pk.get(pk.size() - 1));
        // A fresh decoder assumes the (absent) previous block was short, so the first fed block can return
        // samples that are not real audio; they are dropped. What follows is the continuous decode from the
        // granule position of the page.
        pending = new byte[0];
        pendingPos = 0;
        discard = frame - pages.get(best).granule();
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) throws IOException {
        while (pendingPos >= pending.length) {
            if (!decodeNext()) {
                return -1;
            }
        }
        int n = Math.min(len, pending.length - pendingPos);
        System.arraycopy(pending, pendingPos, buf, off, n);
        pendingPos += n;
        return n;
    }

    private boolean decodeNext() throws IOException {
        while (packets.isEmpty()) {
            if (nextPage >= pages.size()) {
                return false;
            }
            OggFile.Page p = pages.get(nextPage++);
            if (p.serial() != serial) {
                continue;
            }
            packets.addAll(assembler.feed(p.continued(), ogg.lacing(p), ogg.body(p)));
        }
        feed(packets.poll());
        return true;
    }

    /** Decodes one audio packet into {@link #pending}. */
    private void feed(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (block.synthesis(packet(data)) == 0) {
            dsp.synthesis_blockin(block);
        }
        int n;
        while ((n = dsp.synthesis_pcmout(pcmOut, index)) > 0) {
            int from = 0;
            if (discard > 0) {
                from = (int) Math.min(discard, n);
                discard -= from;
            }
            for (int i = from; i < n; i++) {
                int l = toShort(pcmOut[0][0][index[0] + i]);
                int r = channels > 1 ? toShort(pcmOut[0][1][index[1] + i]) : l;
                out.write(l);
                out.write(l >> 8);
                out.write(r);
                out.write(r >> 8);
            }
            dsp.synthesis_read(n);
        }
        pending = out.toByteArray();
        pendingPos = 0;
    }

    private static int toShort(float f) {
        return StereoPcm.clamp16(Math.round(f * 32767f));
    }

    @Override
    public void close() throws IOException {
        ogg.close();
    }
}
