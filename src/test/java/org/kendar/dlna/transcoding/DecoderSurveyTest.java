package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Header;
import org.jflac.FLACDecoder;
import org.jflac.PCMProcessor;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;
import org.junit.jupiter.api.Test;

/**
 * Spike item 4: every decoder on Java 25, seeking and exact sample counts. Fixtures in src/test/resources/audio were
 * generated once with ffmpeg from a 2 s, 44.1 kHz stereo sine sweep (the opus one at 48 kHz).
 */
class DecoderSurveyTest {

    private static final int SWEEP_FRAMES = 88200;

    // ---------------------------------------------------------------- MP3 / JLayer

    @Test
    void mp3JLayerDecodesCountsFramesWithoutDecodingAndSeeksSampleAccurately() throws Exception {
        byte[] mp3 = TestAudio.readAll(TestAudio.resource("sweep.mp3"));
        TestAudio.Mp3Pcm full = TestAudio.decodeMp3(mp3);
        // header-only walk = exact decoded sample count without decoding
        int headerFrames = 0;
        Bitstream bs = new Bitstream(new ByteArrayInputStream(mp3));
        Header h;
        int spf = 0;
        while ((h = bs.readFrame()) != null) {
            headerFrames++;
            spf = h.version() == Header.MPEG1 ? 1152 : 576;
            bs.closeFrame();
        }
        assertThat(headerFrames).isEqualTo(full.frames());
        assertThat((long) headerFrames * spf).isEqualTo(full.sampleFrames());
        // LAME/Xing tag: exact encoder delay and padding
        int[] dp = lameDelayPadding(mp3);
        System.out.println("[spike] MP3 fixture: frames=" + headerFrames + " decoded sample frames="
                + full.sampleFrames() + " (source 88200) LAME tag delay/padding=" + Arrays.toString(dp)
                + " -> gapless length " + (full.sampleFrames() - dp[0] - dp[1]));
        assertThat(full.sampleRate()).isEqualTo(44100);
        // seek: skip headers to frame k-4 (bit reservoir needs the previous frames), decode, discard the warm-up
        int k = 40;
        int warm = 4;
        Bitstream bs2 = new Bitstream(new ByteArrayInputStream(mp3));
        for (int i = 0; i < k - warm; i++) {
            bs2.readFrame();
            bs2.closeFrame();
        }
        javazoom.jl.decoder.Decoder dec = new javazoom.jl.decoder.Decoder();
        short[] seeked = new short[0];
        Header hh;
        int idx = 0;
        while ((hh = bs2.readFrame()) != null) {
            var sb = (javazoom.jl.decoder.SampleBuffer) dec.decodeFrame(hh, bs2);
            if (idx++ >= warm) {
                int old = seeked.length;
                seeked = Arrays.copyOf(seeked, old + sb.getBufferLength());
                System.arraycopy(sb.getBuffer(), 0, seeked, old, sb.getBufferLength());
            }
            bs2.closeFrame();
        }
        short[] expected = Arrays.copyOfRange(full.samples(), k * 1152 * 2, full.samples().length);
        assertThat(Arrays.equals(seeked, expected)).as("seek with 4 frames of warm-up == continuous decode").isTrue();
    }

    /** Parses the LAME extension of the Xing/Info frame; returns {delay, padding} or {-1,-1}. */
    private static int[] lameDelayPadding(byte[] mp3) {
        Mp3Frames.Scan scan = Mp3Frames.scan(mp3);
        if (scan.frames().isEmpty() || !scan.frames().get(0).xingOrInfo()) {
            return new int[] {-1, -1};
        }
        int off = scan.frames().get(0).offset() + 4 + 32;
        int flags = (mp3[off + 7] & 0xff);
        int p = off + 8;
        if ((flags & 1) != 0) {
            p += 4;
        }
        if ((flags & 2) != 0) {
            p += 4;
        }
        if ((flags & 4) != 0) {
            p += 100;
        }
        if ((flags & 8) != 0) {
            p += 4;
        }
        int d = p + 21;
        int delay = ((mp3[d] & 0xff) << 4) | ((mp3[d + 1] & 0xf0) >> 4);
        int pad = ((mp3[d + 1] & 0x0f) << 8) | (mp3[d + 2] & 0xff);
        return new int[] {delay, pad};
    }

    // ---------------------------------------------------------------- OGG Vorbis / JOrbis

    private record Vorbis(short[] pcm, int channels, int rate, long lastGranule) {
    }

    /** Standard JOrbis decode loop over the whole file; also records the last page granule position. */
    private static Vorbis decodeVorbis(byte[] data) throws IOException {
        SyncState oy = new SyncState();
        StreamState os = new StreamState();
        Page og = new Page();
        Packet op = new Packet();
        Info vi = new Info();
        Comment vc = new Comment();
        DspState vd = new DspState();
        Block vb = new Block(vd);
        oy.init();
        vi.init();
        vc.init();
        int pos = 0;
        int headers = 0;
        boolean inited = false;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long lastGranule = 0;
        float[][][] pcmf = new float[1][][];
        int[] index = new int[16];
        while (true) {
            int res = oy.pageout(og);
            if (res == 0) {
                int n = Math.min(4096, data.length - pos);
                if (n <= 0) {
                    break;
                }
                int i = oy.buffer(n);
                System.arraycopy(data, pos, oy.data, i, n);
                oy.wrote(n);
                pos += n;
                continue;
            }
            if (res < 0) {
                continue;
            }
            if (!inited) {
                os.init(og.serialno());
                inited = true;
            }
            os.pagein(og);
            if (og.granulepos() > 0) {
                lastGranule = og.granulepos();
            }
            while (os.packetout(op) == 1) {
                if (headers < 3) {
                    if (vi.synthesis_headerin(vc, op) < 0) {
                        throw new IOException("bad vorbis header");
                    }
                    headers++;
                    if (headers == 3) {
                        vd.synthesis_init(vi);
                        vb.init(vd);
                    }
                    continue;
                }
                if (vb.synthesis(op) == 0) {
                    vd.synthesis_blockin(vb);
                }
                int samples;
                while ((samples = vd.synthesis_pcmout(pcmf, index)) > 0) {
                    for (int i = 0; i < samples; i++) {
                        for (int c = 0; c < vi.channels; c++) {
                            float f = pcmf[0][c][index[c] + i];
                            int v = Math.max(-32768, Math.min(32767, Math.round(f * 32767f)));
                            pcm.write(v);
                            pcm.write(v >> 8);
                        }
                    }
                    vd.synthesis_read(samples);
                }
            }
        }
        return new Vorbis(TestAudio.toShorts(pcm.toByteArray()), vi.channels, vi.rate, lastGranule);
    }

    @Test
    void oggVorbisJOrbisDecodesAndGranuleGivesTheExactLength() throws Exception {
        byte[] ogg = TestAudio.readAll(TestAudio.resource("sweep.ogg"));
        Vorbis v = decodeVorbis(ogg);
        System.out.println("[spike] Vorbis fixture: rate=" + v.rate() + " channels=" + v.channels()
                + " decoded frames=" + v.pcm().length / v.channels() + " last granule=" + v.lastGranule());
        assertThat(v.rate()).isEqualTo(44100);
        assertThat(v.channels()).isEqualTo(2);
        assertThat(v.lastGranule()).isEqualTo(SWEEP_FRAMES);
        // decoder output may exceed the granule by < one block; the pipeline truncates to the granule count
        assertThat((long) v.pcm().length / 2).isBetween(v.lastGranule() - 2048, v.lastGranule() + 2048);
        double[] x = TestAudio.left(v.pcm(), 2, 1000, 2000);
        double energy = Arrays.stream(x).map(d -> d * d).sum();
        assertThat(energy).isGreaterThan(1);
    }

    @Test
    void vorbisSpiGivesNoFrameLengthSoItIsNotUsableForExactSizes() throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(TestAudio.resource("sweep.ogg").toFile());
        AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, ais.getFormat().getSampleRate(), 16,
                ais.getFormat().getChannels(), ais.getFormat().getChannels() * 2, ais.getFormat().getSampleRate(),
                false);
        try (AudioInputStream dec = AudioSystem.getAudioInputStream(pcm, ais)) {
            byte[] bytes = dec.readAllBytes();
            System.out.println("[spike] vorbisspi: format=" + ais.getFormat() + " frameLength=" + ais.getFrameLength()
                    + " decoded frames=" + bytes.length / 4);
            assertThat(bytes.length).isGreaterThan(0);
            assertThat(ais.getFrameLength()).isEqualTo(AudioSystem.NOT_SPECIFIED);
        }
    }

    // ---------------------------------------------------------------- FLAC / JFLAC

    @Test
    void flacJFlacDecodesWithExactTotalSamplesFromStreamInfoAndSeeks() throws Exception {
        File f = TestAudio.resource("sweep.flac").toFile();
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        StreamInfo info;
        try (InputStream in = new FileInputStream(f)) {
            FLACDecoder dec = new FLACDecoder(in);
            dec.addPCMProcessor(new PCMProcessor() {
                @Override
                public void processStreamInfo(StreamInfo streamInfo) {
                }

                @Override
                public void processPCM(ByteData pcmData) {
                    pcm.write(pcmData.getData(), 0, pcmData.getLen());
                }
            });
            dec.decode();
            info = dec.getStreamInfo();
        }
        System.out.println("[spike] FLAC fixture: total=" + info.getTotalSamples() + " rate=" + info.getSampleRate()
                + " ch=" + info.getChannels() + " bps=" + info.getBitsPerSample() + " decoded frames="
                + pcm.size() / 4);
        assertThat(info.getTotalSamples()).isEqualTo(SWEEP_FRAMES);
        assertThat(pcm.size() / 4).isEqualTo(SWEEP_FRAMES);
        // bit-exact: ffmpeg flac is lossless, so it must equal the WAV sibling sample for sample
        byte[] wavPcm = wavPcm(TestAudio.resource("sweep.wav"));
        assertThat(pcm.toByteArray()).isEqualTo(wavPcm);
        // seeking: JFLAC's seek(long) works on the raw stream position; sample-accurate seeking is done by
        // decoding frames from the start (or from a SEEKTABLE point) and discarding. Measure the brute force cost.
        long t0 = System.nanoTime();
        try (InputStream in = new FileInputStream(f)) {
            FLACDecoder dec = new FLACDecoder(in);
            dec.readMetadata();
            int frames = 0;
            while (!dec.isEOF() && dec.getSamplesDecoded() < 80000) {
                dec.readNextFrame();
                frames++;
            }
            System.out.printf("[spike] FLAC skip-forward to ~80000 samples: %d frames in %.1f ms%n", frames,
                    (System.nanoTime() - t0) / 1e6);
        }
    }

    private static byte[] wavPcm(Path wav) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wav.toFile())) {
            return ais.readAllBytes();
        }
    }

    // ---------------------------------------------------------------- WAV / AIFF / Java Sound

    @Test
    void wavAndAiffDecodeWithExactFrameLengthAndByteExactSeek() throws Exception {
        byte[] wav = wavPcm(TestAudio.resource("sweep.wav"));
        assertThat(wav.length / 4).isEqualTo(SWEEP_FRAMES);
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(TestAudio.resource("sweep.aiff").toFile())) {
            AudioFormat src = ais.getFormat();
            assertThat(ais.getFrameLength()).isEqualTo(SWEEP_FRAMES);
            assertThat(src.isBigEndian()).isTrue();
            AudioFormat le = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16,
                    src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream conv = AudioSystem.getAudioInputStream(le, ais)) {
                byte[] aiff = conv.readAllBytes();
                assertThat(aiff).isEqualTo(wav);
            }
        }
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(TestAudio.resource("sweep.wav").toFile())) {
            assertThat(ais.getFrameLength()).isEqualTo(SWEEP_FRAMES);
            long skipped = ais.skip(12345L * 4);
            assertThat(skipped).isEqualTo(12345L * 4);
            byte[] after = ais.readNBytes(400);
            assertThat(after).isEqualTo(Arrays.copyOfRange(wav, 12345 * 4, 12345 * 4 + 400));
        }
    }

    @Test
    void generatedWavRoundTripsThroughJavaSound() throws Exception {
        byte[] pcm = TestAudio.sine(440, 0.5, 44100, 1000);
        byte[] wav = TestAudio.wav(pcm, 44100, 2);
        Path tmp = Files.createTempFile("spike", ".wav");
        try {
            Files.write(tmp, wav);
            assertThat(wavPcm(tmp)).isEqualTo(pcm);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void mp3SpiGivesNoFrameLengthEither() throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(TestAudio.resource("sweep.mp3").toFile())) {
            System.out.println("[spike] mp3spi: format=" + ais.getFormat() + " frameLength=" + ais.getFrameLength());
            assertThat(ais.getFormat().getEncoding().toString()).containsIgnoringCase("MPEG");
        }
    }
}
