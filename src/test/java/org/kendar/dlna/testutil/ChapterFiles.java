package org.kendar.dlna.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;

/** Small audio files carrying embedded chapters (ID3 CHAP/CTOC, MP4 chpl, Vorbis comments), built in test code. */
public final class ChapterFiles {

    private ChapterFiles() {
    }

    /** One chapter: id, start (ms), end (ms), title (null = no TIT2), performer (null = no TPE1). */
    public record Chap(String id, long start, long end, String title, String performer) {
        public Chap(String id, long start, long end, String title) {
            this(id, start, end, title, null);
        }
    }

    public static byte[] be32(long v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    public static byte[] frame(String id, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(be32(data.length));
        o.writeBytes(new byte[] {0, 0});
        o.writeBytes(data);
        return o.toByteArray();
    }

    public static byte[] text(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        return o.toByteArray();
    }

    public static byte[] chap(Chap c) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(c.id().getBytes(StandardCharsets.ISO_8859_1));
        o.write(0);
        o.writeBytes(be32(c.start()));
        o.writeBytes(be32(c.end()));
        o.writeBytes(be32(0xFFFFFFFFL));
        o.writeBytes(be32(0xFFFFFFFFL));
        if (c.title() != null) {
            o.writeBytes(frame("TIT2", text(c.title())));
        }
        if (c.performer() != null) {
            o.writeBytes(frame("TPE1", text(c.performer())));
        }
        return frame("CHAP", o.toByteArray());
    }

    public static byte[] ctoc(String... children) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("toc".getBytes(StandardCharsets.ISO_8859_1));
        o.write(0);
        o.write(3);   // top level + ordered
        o.write(children.length);
        for (String c : children) {
            o.writeBytes(c.getBytes(StandardCharsets.ISO_8859_1));
            o.write(0);
        }
        return frame("CTOC", o.toByteArray());
    }

    /** {@code sweep.mp3} (2 s) preceded by an ID3v2.3 tag made of the given frames, written to {@code target}. */
    public static Path mp3(Path target, byte[]... frames) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (byte[] f : frames) {
                body.writeBytes(f);
            }
            int size = body.size();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            all.writeBytes(new byte[] {'I', 'D', '3', 3, 0, 0, (byte) ((size >> 21) & 0x7F),
                    (byte) ((size >> 14) & 0x7F), (byte) ((size >> 7) & 0x7F), (byte) (size & 0x7F)});
            all.writeBytes(body.toByteArray());
            Path plain = AudioFiles.fixture("sweep.mp3", target.resolveSibling(target.getFileName() + ".plain"));
            all.writeBytes(Files.readAllBytes(plain));
            Files.delete(plain);
            Files.write(target, all.toByteArray());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An mp3 with the given chapters (and a top-level CTOC listing them in the given order when {@code toc}). */
    public static Path mp3Chapters(Path target, boolean toc, Chap... chaps) {
        List<byte[]> frames = new ArrayList<>();
        String[] ids = new String[chaps.length];
        for (int i = 0; i < chaps.length; i++) {
            frames.add(chap(chaps[i]));
            ids[i] = chaps[i].id();
        }
        if (toc) {
            frames.add(ctoc(ids));
        }
        return mp3(target, frames.toArray(new byte[0][]));
    }

    // ---- MP4 ----

    public static byte[] box(String type, byte[]... children) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] c : children) {
            body.writeBytes(c);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(be32(body.size() + 8L));
        o.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(body.toByteArray());
        return o.toByteArray();
    }

    /** A Nero chpl box; each chapter is {start ms, title}. */
    public static byte[] chpl(Object[]... chapters) {
        ByteArrayOutputStream chpl = new ByteArrayOutputStream();
        chpl.writeBytes(new byte[] {1, 0, 0, 0, 0, 0, 0, 0});
        chpl.write(chapters.length);
        for (Object[] c : chapters) {
            long units = ((Number) c[0]).longValue() * 10_000L;
            for (int s = 56; s >= 0; s -= 8) {
                chpl.write((int) (units >> s));
            }
            byte[] name = ((String) c[1]).getBytes(StandardCharsets.UTF_8);
            chpl.write(name.length);
            chpl.writeBytes(name);
        }
        return box("chpl", chpl.toByteArray());
    }

    /**
     * {@code sweep.m4a} with a chpl box added to the end of its {@code moov/udta} (needs moov to be the last
     * top-level box so no chunk offset moves).
     */
    public static Path m4a(Path target, Object[]... chapters) {
        try {
            byte[] d = Files.readAllBytes(AudioFiles.fixture("sweep.m4a", target));
            int p = 0;
            while (p + 8 <= d.length) {
                long size = readInt(d, p);
                String type = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
                if (type.equals("moov")) {
                    if (p + size != d.length) {
                        throw new IllegalStateException("moov is not the last box of sweep.m4a");
                    }
                    int q = p + 8;
                    while (q + 8 <= p + size) {
                        long cs = readInt(d, q);
                        if (new String(d, q + 4, 4, StandardCharsets.ISO_8859_1).equals("udta")) {
                            if (q + cs != d.length) {
                                throw new IllegalStateException("udta is not the last box of moov");
                            }
                            byte[] add = chpl(chapters);
                            writeInt(d, q, cs + add.length);
                            writeInt(d, p, size + add.length);
                            ByteArrayOutputStream o = new ByteArrayOutputStream();
                            o.write(d, 0, d.length);
                            o.writeBytes(add);
                            Files.write(target, o.toByteArray());
                            return target;
                        }
                        q += (int) cs;
                    }
                    throw new IllegalStateException("no udta in sweep.m4a");
                }
                p += (int) size;
            }
            throw new IllegalStateException("no moov in sweep.m4a");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long readInt(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static void writeInt(byte[] d, int p, long v) {
        System.arraycopy(be32(v), 0, d, p, 4);
    }

    // ---- Vorbis comments ----

    /** A copy of {@code sweep.flac} / {@code sweep.ogg} with the given Vorbis comment fields (id, value, ...). */
    public static Path vorbis(String fixture, Path target, String... idValue) {
        try {
            AudioFiles.fixture(fixture, target);
            AudioFile af = AudioFileIO.read(target.toFile());
            Tag tag = af.getTagOrCreateAndSetDefault();
            VorbisCommentTag vc = tag instanceof FlacTag f ? f.getVorbisCommentTag() : (VorbisCommentTag) tag;
            for (int i = 0; i < idValue.length; i += 2) {
                vc.addField(vc.createField(idValue[i], idValue[i + 1]));
            }
            af.commit();
            return target;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
