package com.example.inventory;

import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserAccountService {

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserSummary> list() {
        return accounts.findAll().stream()
                .map(account -> new UserSummary(account.getUsername(), account.getRole()))
                .toList();
    }

    @Transactional
    public UserSummary create(String username, String password) {
        String normalizedUsername = normalize(username);
        if (accounts.existsByUsername(normalizedUsername)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        UserAccount account = accounts.save(new UserAccount(
                normalizedUsername,
                passwordEncoder.encode(password),
                UserAccount.ROLE_USER));
        return new UserSummary(account.getUsername(), account.getRole());
    }

    @Transactional
    public void delete(String username, String currentAdmin) {
        String normalizedUsername = normalize(username);
        if (normalizedUsername.equals(normalize(currentAdmin))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot delete your own account");
        }
        UserAccount account = accounts.findByUsername(normalizedUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (UserAccount.ROLE_ADMIN.equals(account.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator accounts cannot be deleted here");
        }
        accounts.delete(account);
    }

    static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public record UserSummary(String username, String role) {
    }
}
