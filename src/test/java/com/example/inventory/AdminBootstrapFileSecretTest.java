package com.example.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AdminBootstrapFileSecretTest {

    private static final String BOOTSTRAP_PASSWORD = UUID.randomUUID() + "-bootstrap";
    private static final Path SECRET_DIRECTORY = Path.of("target", "admin-bootstrap-file-test");
    private static final Path USER_FILE = SECRET_DIRECTORY.resolve("admin_user");
    private static final Path PASSWORD_FILE = SECRET_DIRECTORY.resolve("admin_password");
    private static final String DATABASE_URL =
            "jdbc:sqlite:target/admin-bootstrap-file-" + UUID.randomUUID() + ".db";

    static {
        try {
            Files.createDirectories(SECRET_DIRECTORY);
            Files.writeString(USER_FILE, "file-admin\n");
            Files.writeString(PASSWORD_FILE, BOOTSTRAP_PASSWORD + "\n");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private ProductRepository products;

    @Autowired
    private ProductImageStorage images;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("ADMIN_USER_FILE", USER_FILE::toString);
        registry.add("ADMIN_PASSWORD_FILE", PASSWORD_FILE::toString);
    }

    @Test
    void bootstrapsAdminFromSecretFilesAndHashesPassword() throws IOException {
        UserAccount admin = accounts.findByUsername("file-admin").orElseThrow();
        assertEquals(UserAccount.ROLE_ADMIN, admin.getRole());
        assertTrue(passwordEncoder.matches(BOOTSTRAP_PASSWORD, admin.getPasswordHash()));
        assertEquals(20, products.count());
        for (Product product : products.findAll()) {
            assertNotNull(product.getImageFilename());
            assertTrue(images.read(product.getImageFilename()).length > 0);
        }
    }
}
