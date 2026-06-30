package com.gamerin.backend.domain.post.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaUploadSecurityService {

    private static final long MAX_IMAGE_FILE_SIZE_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 6_000;
    private static final long MAX_IMAGE_PIXELS = 24_000_000L;
    private static final int STORED_IMAGE_MAX_DIMENSION = 2_048;
    private static final int MIN_STORED_IMAGE_MAX_DIMENSION = 1_024;
    private static final int MAX_STORED_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int STORED_PROFILE_AVATAR_MAX_DIMENSION = 512;
    private static final int MIN_STORED_PROFILE_AVATAR_MAX_DIMENSION = 256;
    private static final int MAX_STORED_PROFILE_AVATAR_BYTES = 700 * 1024;
    private static final int STORED_PROFILE_COVER_MAX_DIMENSION = 1_920;
    private static final int MIN_STORED_PROFILE_COVER_MAX_DIMENSION = 960;
    private static final int MAX_STORED_PROFILE_COVER_BYTES = 2 * 1024 * 1024;
    private static final float[] JPEG_QUALITIES = {0.85f, 0.75f, 0.65f};

    private final AnimatedGifProcessor animatedGifProcessor;

    private static final Set<String> MP4_FAMILY_EXTENSIONS = Set.of(".mp4", ".mov", ".m4v");
    private static final Set<String> MP4_FAMILY_CONTENT_TYPES = Set.of(
            "video/mp4",
            "video/quicktime",
            "video/x-m4v"
    );

    static {
        ImageIO.setUseCache(false);
    }

    public MediaUploadSecurityService(AnimatedGifProcessor animatedGifProcessor) {
        this.animatedGifProcessor = animatedGifProcessor;
    }

    public MediaStorageService.PreparedMediaFile prepareImage(MultipartFile file) {
        ImageFormat declaredFormat = resolveDeclaredImageFormat(file);
        if (declaredFormat == ImageFormat.GIF) {
            return animatedGifProcessor.prepare(file);
        }
        return prepareStaticImage(
                file,
                declaredFormat,
                STORED_IMAGE_MAX_DIMENSION,
                MIN_STORED_IMAGE_MAX_DIMENSION,
                MAX_STORED_IMAGE_BYTES
        );
    }

    public MediaStorageService.PreparedMediaFile prepareProfileAvatarImage(MultipartFile file) {
        ImageFormat declaredFormat = resolveProfileImageFormat(file);
        return prepareStaticImage(
                file,
                declaredFormat,
                STORED_PROFILE_AVATAR_MAX_DIMENSION,
                MIN_STORED_PROFILE_AVATAR_MAX_DIMENSION,
                MAX_STORED_PROFILE_AVATAR_BYTES
        );
    }

    public MediaStorageService.PreparedMediaFile prepareProfileCoverImage(MultipartFile file) {
        ImageFormat declaredFormat = resolveProfileImageFormat(file);
        return prepareStaticImage(
                file,
                declaredFormat,
                STORED_PROFILE_COVER_MAX_DIMENSION,
                MIN_STORED_PROFILE_COVER_MAX_DIMENSION,
                MAX_STORED_PROFILE_COVER_BYTES
        );
    }

    private MediaStorageService.PreparedMediaFile prepareStaticImage(
            MultipartFile file,
            ImageFormat declaredFormat,
            int maxDimension,
            int minDimension,
            int maxBytes
    ) {
        assertImageMagicMatches(file, declaredFormat);

        BufferedImage image = readImage(file, declaredFormat);
        byte[] compressed = writeCompressedJpeg(image, maxDimension, minDimension, maxBytes);

        return new MediaStorageService.PreparedMediaFile(compressed, ".jpg");
    }

    public void assertImageFileSafe(MultipartFile file) {
        ImageFormat declaredFormat = resolveDeclaredImageFormat(file);
        if (declaredFormat == ImageFormat.GIF) {
            animatedGifProcessor.validate(file);
            return;
        }
        assertImageMagicMatches(file, declaredFormat);
        readImage(file, declaredFormat);
    }

    private ImageFormat resolveProfileImageFormat(MultipartFile file) {
        ImageFormat declaredFormat = resolveDeclaredImageFormat(file);
        if (declaredFormat == ImageFormat.GIF) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile image must be JPEG or PNG.");
        }
        return declaredFormat;
    }

    public void assertVideoFileSafe(MultipartFile file) {
        String extension = normalizedExtension(file);
        if (!MP4_FAMILY_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video file must be MP4, MOV, or M4V.");
        }

        String contentType = normalizedContentType(file);
        if (contentType != null && !MP4_FAMILY_CONTENT_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video content type does not match an allowed video format.");
        }

        byte[] header = readHeader(file, 12);
        if (header.length < 12 || header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video file header is invalid.");
        }
    }

    private ImageFormat resolveDeclaredImageFormat(MultipartFile file) {
        if (file.getSize() > MAX_IMAGE_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file must be 20MB or smaller.");
        }

        String extension = normalizedExtension(file);
        ImageFormat extensionFormat = ImageFormat.fromExtension(extension);
        if (extensionFormat == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file must be JPEG, PNG, or GIF.");
        }

        String contentType = normalizedContentType(file);
        if (contentType != null && !extensionFormat.contentTypes().contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image content type does not match the file extension.");
        }

        return extensionFormat;
    }

    private void assertImageMagicMatches(MultipartFile file, ImageFormat expectedFormat) {
        byte[] header = readHeader(file, expectedFormat.magicHeaderLength());
        if (!expectedFormat.matchesMagic(header)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file header does not match the declared format.");
        }
    }

    private BufferedImage readImage(MultipartFile file, ImageFormat expectedFormat) {
        try (InputStream inputStream = file.getInputStream();
             ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            if (imageInputStream == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file could not be read.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(expectedFormat.readerFormatName());
            if (!readers.hasNext()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image format is not supported by the server.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInputStream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateImageDimensions(width, height);

                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file could not be decoded.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read image file.", ex);
        }
    }

    private void validateImageDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image dimensions are invalid.");
        }
        if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image width and height must be 6000px or smaller.");
        }
        if ((long) width * height > MAX_IMAGE_PIXELS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image must be 24 megapixels or smaller.");
        }
    }

    private BufferedImage resizeAndFlatten(BufferedImage source, int maxDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longestSide = Math.max(width, height);
        double scale = longestSide > maxDimension ? maxDimension / (double) longestSide : 1.0;

        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        return target;
    }

    private byte[] writeCompressedJpeg(
            BufferedImage source,
            int initialMaxDimension,
            int minimumMaxDimension,
            int maxBytes
    ) {
        int maxDimension = initialMaxDimension;
        while (maxDimension >= minimumMaxDimension) {
            BufferedImage resized = resizeAndFlatten(source, maxDimension);
            for (float quality : JPEG_QUALITIES) {
                byte[] jpegBytes = writeJpeg(resized, quality);
                if (jpegBytes.length <= maxBytes) {
                    return jpegBytes;
                }
            }
            maxDimension = maxDimension / 2;
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Compressed image is too large.");
    }

    private byte[] writeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "JPEG writer is not available.");
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(imageOutputStream);

            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(quality);
            }

            writer.write(null, new IIOImage(image, null, null), writeParam);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to compress image.", ex);
        } finally {
            writer.dispose();
        }
    }

    private byte[] readHeader(MultipartFile file, int length) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(length);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read media file header.", ex);
        }
    }

    private String normalizedExtension(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }

        int extensionIndex = originalFilename.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == originalFilename.length() - 1) {
            return "";
        }

        return originalFilename.substring(extensionIndex).toLowerCase(Locale.ROOT);
    }

    private String normalizedContentType(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType == null || contentType.isBlank()
                ? null
                : contentType.toLowerCase(Locale.ROOT);
    }

    private enum ImageFormat {
        JPEG(Set.of(".jpg", ".jpeg"), Set.of("image/jpeg"), "jpeg"),
        PNG(Set.of(".png"), Set.of("image/png"), "png"),
        GIF(Set.of(".gif"), Set.of("image/gif"), "gif");

        private final Set<String> extensions;
        private final Set<String> contentTypes;
        private final String readerFormatName;

        ImageFormat(Set<String> extensions, Set<String> contentTypes, String readerFormatName) {
            this.extensions = extensions;
            this.contentTypes = contentTypes;
            this.readerFormatName = readerFormatName;
        }

        private static ImageFormat fromExtension(String extension) {
            for (ImageFormat format : values()) {
                if (format.extensions.contains(extension)) {
                    return format;
                }
            }
            return null;
        }

        private Set<String> contentTypes() {
            return contentTypes;
        }

        private String readerFormatName() {
            return readerFormatName;
        }

        private int magicHeaderLength() {
            if (this == JPEG) {
                return 3;
            }
            return this == PNG ? 8 : 6;
        }

        private boolean matchesMagic(byte[] header) {
            if (this == JPEG) {
                return header.length >= 3
                        && (header[0] & 0xff) == 0xff
                        && (header[1] & 0xff) == 0xd8
                        && (header[2] & 0xff) == 0xff;
            }

            if (this == GIF) {
                return header.length >= 6
                        && header[0] == 'G'
                        && header[1] == 'I'
                        && header[2] == 'F'
                        && header[3] == '8'
                        && (header[4] == '7' || header[4] == '9')
                        && header[5] == 'a';
            }

            return header.length >= 8
                    && (header[0] & 0xff) == 0x89
                    && header[1] == 'P'
                    && header[2] == 'N'
                    && header[3] == 'G'
                    && (header[4] & 0xff) == 0x0d
                    && (header[5] & 0xff) == 0x0a
                    && (header[6] & 0xff) == 0x1a
                    && (header[7] & 0xff) == 0x0a;
        }
    }
}
