package com.example.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/users")
public class AdminPageController {

    private final UserAccountService userAccounts;

    public AdminPageController(UserAccountService userAccounts) {
        this.userAccounts = userAccounts;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", userAccounts.list());
        model.addAttribute("newUser", new CreateUserForm());
        return "users";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("newUser") CreateUserForm form,
            BindingResult errors,
            Model model) {
        if (errors.hasErrors()) {
            model.addAttribute("users", userAccounts.list());
            return "users";
        }
        try {
            userAccounts.create(form.getUsername(), form.getPassword());
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 409) {
                throw exception;
            }
            errors.rejectValue("username", "duplicate", "That username already exists.");
            model.addAttribute("users", userAccounts.list());
            return "users";
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{username}/delete")
    public String delete(
            @PathVariable String username,
            Principal principal,
            RedirectAttributes redirectAttributes) {
        userAccounts.delete(username, principal.getName());
        redirectAttributes.addFlashAttribute("message", "User account deleted.");
        return "redirect:/admin/users";
    }

    public static class CreateUserForm {
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "[A-Za-z0-9._-]+")
        private String username;

        @NotBlank
        @Size(min = 12, max = 72)
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
