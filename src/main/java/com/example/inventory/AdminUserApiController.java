package com.example.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserApiController {

    private final UserAccountService userAccounts;

    public AdminUserApiController(UserAccountService userAccounts) {
        this.userAccounts = userAccounts;
    }

    @GetMapping
    public List<UserAccountService.UserSummary> list() {
        return userAccounts.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserAccountService.UserSummary create(@Valid @RequestBody CreateUserRequest request) {
        return userAccounts.create(request.username(), request.password());
    }

    @DeleteMapping("/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String username, Principal principal) {
        userAccounts.delete(username, principal.getName());
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
            @NotBlank @Size(min = 12, max = 72) String password) {
    }
}
