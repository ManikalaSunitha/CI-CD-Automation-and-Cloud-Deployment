package com.example.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ProductCatalogBootstrapTest {

    @Test
    void addsBundledImagesToExistingSeedProductsWithoutChangingCustomProducts() {
        ProductRepository products = mock(ProductRepository.class);
        ProductImageStorage images = mock(ProductImageStorage.class);
        Product seededProduct = new Product("Everyday Ceramic Mug", "Sample item", new BigDecimal("18.00"));
        Product customProduct = new Product("Custom item", "User item", new BigDecimal("10.00"));
        when(products.count()).thenReturn(2L);
        when(products.findAll()).thenReturn(List.of(seededProduct, customProduct));
        when(images.storeSeedImage(1)).thenReturn("seeded-image.jpg");

        new ProductCatalogBootstrap(products, images).run(new DefaultApplicationArguments());

        assertEquals("seeded-image.jpg", seededProduct.getImageFilename());
        assertNull(customProduct.getImageFilename());
        verify(images).storeSeedImage(1);
    }
}
