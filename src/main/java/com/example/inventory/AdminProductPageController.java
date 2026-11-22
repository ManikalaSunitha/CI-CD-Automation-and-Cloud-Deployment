package com.example.inventory;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/admin/products")
public class AdminProductPageController {

    private final ProductRepository products;
    private final ProductImageStorage images;

    public AdminProductPageController(ProductRepository products, ProductImageStorage images) {
        this.products = products;
        this.images = images;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("products", products.findAll());
        return "admin-products";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("product", new ProductForm());
        return "admin-product-form";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("product") ProductForm form,
            BindingResult errors,
            @RequestParam(name = "image", required = false) MultipartFile image,
            Model model) {
        if (errors.hasErrors()) {
            return "admin-product-form";
        }
        Product product = form.toProduct();
        if (image != null && !image.isEmpty()) {
            try {
                product.setImageFilename(images.store(image));
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 400) {
                    throw exception;
                }
                errors.rejectValue("name", "image.invalid", exception.getMessage());
                return "admin-product-form";
            }
        }
        products.save(product);
        return "redirect:/admin/products";
    }

    @PostMapping("/{id}/image")
    public String uploadImage(
            @PathVariable Long id,
            @RequestParam("image") MultipartFile image,
            RedirectAttributes redirectAttributes) {
        Product product = products.findById(id).orElseThrow();
        String oldImage = product.getImageFilename();
        try {
            product.setImageFilename(images.store(image));
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 400) {
                throw exception;
            }
            redirectAttributes.addFlashAttribute("error", exception.getReason());
            return "redirect:/admin/products";
        }
        products.save(product);
        if (oldImage != null) {
            images.delete(oldImage);
        }
        redirectAttributes.addFlashAttribute("message", "Product image updated.");
        return "redirect:/admin/products";
    }
}
