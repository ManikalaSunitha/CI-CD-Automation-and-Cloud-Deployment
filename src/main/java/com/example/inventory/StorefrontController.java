package com.example.inventory;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class StorefrontController {

    private static final String CART_SESSION_KEY = "cart";

    private final ProductRepository products;

    public StorefrontController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping("/")
    public String store(Model model, HttpSession session) {
        Map<Long, Integer> cart = cart(session);
        model.addAttribute("products", products.findAll());
        model.addAttribute("cartCount", cart.values().stream().mapToInt(Integer::intValue).sum());
        return "store";
    }

    @PostMapping("/cart/items/{productId}")
    public String addToCart(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "1") int quantity,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        if (quantity < 1 || quantity > 99) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be between 1 and 99");
        }
        Product product = products.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        Map<Long, Integer> cart = cart(session);
        int newQuantity = cart.getOrDefault(productId, 0) + quantity;
        if (newQuantity > 99) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart quantity cannot exceed 99 per product");
        }
        cart.put(productId, newQuantity);
        redirectAttributes.addFlashAttribute("message", product.getName() + " added to your cart.");
        return "redirect:/";
    }

    @GetMapping("/cart")
    public String viewCart(Model model, HttpSession session) {
        Map<Long, Integer> cart = cart(session);
        List<CartItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : cart.entrySet()) {
            products.findById(entry.getKey()).ifPresent(product -> items.add(new CartItem(product, entry.getValue())));
        }
        for (CartItem item : items) {
            total = total.add(item.lineTotal());
        }
        model.addAttribute("items", items);
        model.addAttribute("total", total);
        model.addAttribute("cartCount", cart.values().stream().mapToInt(Integer::intValue).sum());
        return "cart";
    }

    @PostMapping("/cart/items/{productId}/remove")
    public String removeFromCart(@PathVariable Long productId, HttpSession session) {
        cart(session).remove(productId);
        return "redirect:/cart";
    }

    @PostMapping("/cart/clear")
    public String clearCart(HttpSession session) {
        cart(session).clear();
        return "redirect:/cart";
    }

    private Map<Long, Integer> cart(HttpSession session) {
        @SuppressWarnings("unchecked")
        Map<Long, Integer> cart = (Map<Long, Integer>) session.getAttribute(CART_SESSION_KEY);
        if (cart == null) {
            cart = new LinkedHashMap<>();
            session.setAttribute(CART_SESSION_KEY, cart);
        }
        return cart;
    }

    public record CartItem(Product product, int quantity) {
        public BigDecimal lineTotal() {
            return product.getPrice().multiply(BigDecimal.valueOf(quantity));
        }
    }
}
