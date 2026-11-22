package com.example.inventory;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductImageController {

    private final ProductImageStorage images;

    public ProductImageController(ProductImageStorage images) {
        this.images = images;
    }

    @GetMapping("/images/{filename}")
    public ResponseEntity<byte[]> get(@PathVariable String filename) throws IOException {
        MediaType type = filename.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        try {
            return ResponseEntity.ok()
                    .contentType(type)
                    .cacheControl(CacheControl.noCache())
                    .body(images.read(filename));
        } catch (NoSuchFileException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
