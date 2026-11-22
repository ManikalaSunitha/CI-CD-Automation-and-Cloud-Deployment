package com.example.inventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String usernameFile;
    private final String passwordFile;

    public AdminBootstrap(
            UserAccountRepository accounts,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.admin-username:admin}") String username,
            @Value("${app.security.admin-password:}") String password,
            @Value("${ADMIN_USER_FILE:}") String usernameFile,
            @Value("${ADMIN_PASSWORD_FILE:}") String passwordFile) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.usernameFile = usernameFile;
        this.passwordFile = passwordFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.existsByRole(UserAccount.ROLE_ADMIN)) {
            return;
        }

        String initialUsername = UserAccountService.normalize(readSecret(usernameFile, username, "ADMIN_USER_FILE"));
        String initialPassword = readSecret(passwordFile, password, "ADMIN_PASSWORD_FILE");
        if (!initialUsername.matches("[a-z0-9._-]{3,50}") || initialPassword.length() < 12 || initialPassword.length() > 72) {
            throw new IllegalStateException(
                    "Configure an admin username (3-50 letters, numbers, dots, underscores or hyphens) and a 12-72 character password using environment variables or secret files");
        }

        UserAccount existing = accounts.findByUsername(initialUsername).orElse(null);
        if (existing == null) {
            accounts.save(new UserAccount(initialUsername, passwordEncoder.encode(initialPassword), UserAccount.ROLE_ADMIN));
        } else if (!UserAccount.ROLE_ADMIN.equals(existing.getRole())) {
            throw new IllegalStateException("The configured ADMIN_USER already exists as a regular user");
        }
    }

    private String readSecret(String file, String fallback, String settingName) {
        if (file.isBlank()) {
            return fallback;
        }
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).replaceFirst("\\R$", "");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + settingName, exception);
        }
    }
}
