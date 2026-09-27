package com.example.gym.face;

import com.example.gym.common.error.CommonExceptions;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Validates and normalises face photos: JPEG/PNG only, decoded with ImageIO (corrupt files are
 * rejected), EXIF rotation applied, metadata dropped, long side capped and re-encoded as a JPEG
 * small enough for the device face buffer (~120 KB).
 */
public final class FaceImageProcessor {

    public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    public static final int MAX_SIDE = 640;
    public static final int TARGET_BYTES = 100 * 1024;
    public static final int MIN_SIDE_MANUAL = 200;
    public static final int MIN_SIDE_DEVICE = 64;

    private FaceImageProcessor() {
    }

    public static byte[] normalise(byte[] input, int minSide) {
        if (input == null || input.length == 0) {
            throw CommonExceptions.badRequest("Photo is empty");
        }
        if (input.length > MAX_UPLOAD_BYTES) {
            throw CommonExceptions.badRequest("Photo is larger than 10 MB");
        }
        boolean jpeg = isJpeg(input);
        if (!jpeg && !isPng(input)) {
            throw CommonExceptions.badRequest("Photo must be a JPEG or PNG image");
        }
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(input));
        } catch (IOException | RuntimeException ex) {
            decoded = null;
        }
        if (decoded == null) {
            throw CommonExceptions.badRequest("Photo could not be read (corrupt or unsupported image)");
        }
        if (Math.min(decoded.getWidth(), decoded.getHeight()) < minSide) {
            throw CommonExceptions.badRequest("Photo is too small (minimum " + minSide + " px)");
        }
        BufferedImage resized = resize(decoded, MAX_SIDE);
        BufferedImage oriented = jpeg ? applyOrientation(resized, exifOrientation(input)) : resized;
        return encodeUnder(oriented, TARGET_BYTES);
    }

    /**
     * Like {@link #normalise} but keeps a device JPEG byte-identical when it already fits the size
     * and dimension limits (so its sha256 matches what the gateway saw on the device).
     */
    public static byte[] normaliseFromDevice(byte[] input) {
        if (input != null && input.length > 0 && input.length <= TARGET_BYTES && isJpeg(input)
                && exifOrientation(input) <= 1) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(input));
                if (img != null && Math.max(img.getWidth(), img.getHeight()) <= MAX_SIDE
                        && Math.min(img.getWidth(), img.getHeight()) >= MIN_SIDE_DEVICE) {
                    return input;
                }
            } catch (IOException | RuntimeException ignored) {
                // fall through to full validation, which reports the error
            }
        }
        return normalise(input, MIN_SIDE_DEVICE);
    }

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static BufferedImage resize(BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encodeUnder(BufferedImage img, int targetBytes) {
        BufferedImage current = img;
        for (int shrink = 0; shrink < 4; shrink++) {
            for (float q = 0.85f; q >= 0.45f; q -= 0.1f) {
                byte[] bytes = writeJpeg(current, q);
                if (bytes.length <= targetBytes) {
                    return bytes;
                }
            }
            current = resize(current, (int) (Math.max(current.getWidth(), current.getHeight()) * 0.8));
        }
        throw CommonExceptions.badRequest("Photo could not be compressed to the device size limit");
    }

    private static byte[] writeJpeg(BufferedImage img, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } catch (IOException ex) {
            throw new IllegalStateException("JPEG encode failed", ex);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    private static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int sy = 0; sy < h; sy++) {
            for (int sx = 0; sx < w; sx++) {
                int dx;
                int dy;
                switch (orientation) {
                    case 2 -> { dx = w - 1 - sx; dy = sy; }
                    case 3 -> { dx = w - 1 - sx; dy = h - 1 - sy; }
                    case 4 -> { dx = sx; dy = h - 1 - sy; }
                    case 5 -> { dx = sy; dy = sx; }
                    case 6 -> { dx = h - 1 - sy; dy = sx; }
                    case 7 -> { dx = h - 1 - sy; dy = w - 1 - sx; }
                    default -> { dx = sy; dy = w - 1 - sx; }
                }
                out.setRGB(dx, dy, src.getRGB(sx, sy));
            }
        }
        return out;
    }

    /** Reads the EXIF Orientation tag (0x0112) from a JPEG's APP1 segment; 1 when absent. */
    static int exifOrientation(byte[] b) {
        int i = 2;
        while (i + 4 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return 1;
            }
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            int seg = i + 4;
            if (marker == 0xE1 && seg + 14 < b.length
                    && b[seg] == 'E' && b[seg + 1] == 'x' && b[seg + 2] == 'i' && b[seg + 3] == 'f') {
                int tiff = seg + 6;
                boolean le = b[tiff] == 'I';
                int ifd = tiff + readInt(b, tiff + 4, le);
                if (ifd + 2 > b.length) {
                    return 1;
                }
                int entries = readShort(b, ifd, le);
                for (int e = 0; e < entries; e++) {
                    int entry = ifd + 2 + e * 12;
                    if (entry + 12 > b.length) {
                        return 1;
                    }
                    if (readShort(b, entry, le) == 0x0112) {
                        return readShort(b, entry + 8, le);
                    }
                }
                return 1;
            }
            i = seg + len - 2;
        }
        return 1;
    }

    private static int readShort(byte[] b, int off, boolean le) {
        return le ? (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                : ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int off, boolean le) {
        return le
                ? (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24)
                : ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
