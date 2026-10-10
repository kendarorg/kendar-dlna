package org.kendar.dlna.artwork;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Scales images down to a JPEG thumbnail with headless ImageIO. Never upscales. */
final class Thumbnailer {

    private Thumbnailer() {
    }

    /** @throws IOException when the bytes are not a decodable image */
    static byte[] jpeg(byte[] source, int maxSize) throws IOException {
        BufferedImage in = ImageIO.read(new ByteArrayInputStream(source));
        if (in == null || in.getWidth() <= 0 || in.getHeight() <= 0) {
            throw new IOException("Not a decodable image");
        }
        double scale = Math.min(1.0, (double) maxSize / Math.max(in.getWidth(), in.getHeight()));
        int w = Math.max(1, (int) Math.round(in.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(in.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);   // flatten transparency (PNG art) onto white
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(in.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        } finally {
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(out, null, null), param);
            ios.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** Content type by magic number; JPEG when unknown. */
    static String sniff(byte[] d) {
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length > 3 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return "image/gif";
        }
        if (d.length > 2 && d[0] == 'B' && d[1] == 'M') {
            return "image/bmp";
        }
        return "image/jpeg";
    }
}
