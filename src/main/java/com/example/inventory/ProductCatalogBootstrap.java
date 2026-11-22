package com.example.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProductCatalogBootstrap implements ApplicationRunner {

    private static final Map<String, Integer> SEED_IMAGE_NUMBERS = Map.ofEntries(
            Map.entry("Everyday Ceramic Mug", 1),
            Map.entry("Cloud Knit Throw", 2),
            Map.entry("Cedar & Citrus Candle", 3),
            Map.entry("Canvas Market Tote", 4),
            Map.entry("Sunday Journal", 5),
            Map.entry("Arc Desk Lamp", 6),
            Map.entry("Ripple Glass Set", 7),
            Map.entry("Linen Cushion Cover", 8),
            Map.entry("Terra Plant Pot", 9),
            Map.entry("Daily Brew Coffee", 10),
            Map.entry("Pocket Bluetooth Speaker", 11),
            Map.entry("Woven Storage Basket", 12),
            Map.entry("Soft Cover Sketchbook", 13),
            Map.entry("Stainless Travel Bottle", 14),
            Map.entry("Olive Wood Serving Board", 15),
            Map.entry("Botanical Hand Wash", 16),
            Map.entry("Waffle Cotton Hand Towel", 17),
            Map.entry("Minimal Wall Clock", 18),
            Map.entry("Weekend Card Set", 19),
            Map.entry("Amber Glass Carafe", 20));

    private final ProductRepository products;
    private final ProductImageStorage images;

    public ProductCatalogBootstrap(ProductRepository products, ProductImageStorage images) {
        this.products = products;
        this.images = images;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (products.count() != 0) {
            products.findAll().forEach(this::addMissingSeedImage);
            return;
        }

        products.saveAll(List.of(
                product(1, "Everyday Ceramic Mug", "A hand-finished stoneware mug for slow mornings.", "18.00"),
                product(2, "Cloud Knit Throw", "A soft, textured throw made for cozy evenings.", "68.00"),
                product(3, "Cedar & Citrus Candle", "Bright citrus balanced with warm cedarwood.", "26.00"),
                product(4, "Canvas Market Tote", "A sturdy everyday tote with roomy inside pockets.", "32.00"),
                product(5, "Sunday Journal", "A cloth-bound notebook for notes, plans, and ideas.", "16.00"),
                product(6, "Arc Desk Lamp", "A clean-lined reading lamp with a warm ambient glow.", "84.00"),
                product(7, "Ripple Glass Set", "Four versatile drinking glasses with a gentle ripple finish.", "34.00"),
                product(8, "Linen Cushion Cover", "Relaxed natural linen to refresh a favorite corner.", "29.00"),
                product(9, "Terra Plant Pot", "A matte ceramic planter with a matching drip tray.", "24.00"),
                product(10, "Daily Brew Coffee", "A smooth medium roast with cocoa and caramel notes.", "21.00"),
                product(11, "Pocket Bluetooth Speaker", "A compact speaker for music around the home.", "59.00"),
                product(12, "Woven Storage Basket", "Handwoven cotton storage for blankets and everyday bits.", "42.00"),
                product(13, "Soft Cover Sketchbook", "Heavyweight, acid-free pages for sketching and journaling.", "19.00"),
                product(14, "Stainless Travel Bottle", "A double-wall insulated bottle for hot or cold drinks.", "36.00"),
                product(15, "Olive Wood Serving Board", "A naturally grained board for serving and prep.", "48.00"),
                product(16, "Botanical Hand Wash", "A gentle plant-based wash with a fresh herbal scent.", "14.00"),
                product(17, "Waffle Cotton Hand Towel", "A quick-drying cotton towel with a tactile waffle weave.", "22.00"),
                product(18, "Minimal Wall Clock", "A quiet, easy-to-read clock with a natural wood frame.", "54.00"),
                product(19, "Weekend Card Set", "A set of twelve illustrated cards for thoughtful notes.", "15.00"),
                product(20, "Amber Glass Carafe", "A softly tinted glass carafe for the table or bedside.", "31.00")));
    }

    private void addMissingSeedImage(Product product) {
        Integer imageNumber = SEED_IMAGE_NUMBERS.get(product.getName());
        if (imageNumber != null && product.getImageFilename() == null) {
            product.setImageFilename(images.storeSeedImage(imageNumber));
        }
    }

    private Product product(int imageNumber, String name, String description, String price) {
        Product product = new Product(name, description, new BigDecimal(price));
        product.setImageFilename(images.storeSeedImage(imageNumber));
        return product;
    }
}
