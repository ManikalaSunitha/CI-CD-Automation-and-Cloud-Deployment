package com.example.inventory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ProductImageStorage {

    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000;
    private static final int SEED_IMAGE_COUNT = 20;

    private final Path directory;

    public ProductImageStorage(@Value("${app.uploads.directory:uploads}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile upload) {
        if (upload == null || upload.isEmpty() || upload.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty PNG or JPEG image smaller than 5 MB.");
        }

        try {
            byte[] content = upload.getBytes();
            String extension;
            try {
                extension = validateImage(content);
            } catch (IOException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid PNG or JPEG image.", exception);
            }
            return persist(content, extension);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save product image", exception);
        }
    }

    public String storeSeedImage(int productNumber) {
        if (productNumber < 1 || productNumber > SEED_IMAGE_COUNT) {
            throw new IllegalArgumentException("No bundled product image exists for product " + productNumber);
        }

        String resourceName = "product-images/product-%02d.jpg".formatted(productNumber);
        try (InputStream input = new ClassPathResource(resourceName).getInputStream()) {
            byte[] content = input.readAllBytes();
            String extension = validateImage(content);
            return persist(content, extension);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not install bundled product image " + resourceName, exception);
        }
    }

    public byte[] read(String filename) throws IOException {
        if (filename == null || !filename.matches("[a-f0-9-]{36}\\.(png|jpg)")) {
            throw new NoSuchFileException("Invalid image name");
        }
        Path image = directory.resolve(filename).normalize();
        if (!image.startsWith(directory)) {
            throw new NoSuchFileException("Invalid image name");
        }
        return Files.readAllBytes(image);
    }

    public void delete(String filename) {
        if (filename == null || !filename.matches("[a-f0-9-]{36}\\.(png|jpg)")) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve(filename).normalize());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not delete old product image", exception);
        }
    }

    private String persist(byte[] content, String extension) throws IOException {
        String filename = UUID.randomUUID() + extension;
        Files.createDirectories(directory);
        Files.write(directory.resolve(filename), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return filename;
    }

    private String validateImage(byte[] content) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid PNG or JPEG image.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid PNG or JPEG image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PNG and JPEG images are supported.");
                }
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image dimensions must be at most 20 megapixels.");
                }
                if (reader.read(0) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid PNG or JPEG image.");
                }
                return format.equals("png") ? ".png" : ".jpg";
            } finally {
                reader.dispose();
            }
        }
    }
}
