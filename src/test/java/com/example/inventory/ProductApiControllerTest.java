package com.example.inventory;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.security.admin-username=test-admin",
        "app.security.admin-password=test-admin-password",
        "spring.datasource.url=jdbc:sqlite:target/test-inventory.db"
})
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class ProductApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository products;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearProducts() {
        products.deleteAll();
    }

    @Test
    void supportsProductCrud() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Notebook","description":"A5 ruled","price":4.50}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Notebook")))
                .andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is((int) id)));
        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description", is("A5 ruled")));
        mockMvc.perform(put("/api/products/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Journal","description":"A4 plain","price":8.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Journal")));
        mockMvc.perform(delete("/api/products/{id}", id))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidProduct() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","description":"invalid","price":-1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateListAndDeleteUsers() throws Exception {
        String username = "test-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AdminUserApiController.CreateUserRequest(username, "test-password-123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username", is(username)))
                .andExpect(jsonPath("$.role", is("USER")));
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == '" + username + "')].passwordHash").doesNotExist());
        mockMvc.perform(delete("/api/admin/users/{username}", username))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanOpenUserManagementPage() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invite a teammate")));
    }

    @Test
    void bootstrapAdminLoginRedirectsToProductCreation() throws Exception {
        mockMvc.perform(formLogin().user("test-admin").password("test-admin-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/products/new"));

        mockMvc.perform(get("/admin/products/new").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Create product")));
    }

    @Test
    @WithMockUser
    void regularUserCannotCreateAccounts() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new-user","password":"long-enough-password"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    void regularUserCannotCreateProducts() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Unauthorized","description":"No","price":1}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void storefrontAndCartArePublic() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Store")));
        mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your cart is waiting")));
    }

    @Test
    @WithAnonymousUser
    void bootstrapAssetsAreServedLocally() throws Exception {
        mockMvc.perform(get("/webjars/bootstrap/5.3.3/css/bootstrap.min.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    @Test
    @WithAnonymousUser
    void customerCanAddProductToSessionCart() throws Exception {
        Product product = products.save(new Product("Cart item", "Store product", new java.math.BigDecimal("3.25")));
        MvcResult result = mockMvc.perform(post("/cart/items/{id}", product.getId())
                        .param("quantity", "2")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        mockMvc.perform(get("/cart").session((org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Cart item")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("6.50")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateProductWithImage() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pN8AAAAASUVORK5CYII=");
        MockMultipartFile image = new MockMultipartFile("image", "pixel.png", "image/png", png);
        mockMvc.perform(multipart("/admin/products")
                        .file(image)
                        .with(csrf())
                        .param("name", "Image product")
                        .param("description", "A product with a picture")
                        .param("price", "2.50"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin/products"));
        Product product = products.findAll().stream()
                .filter(item -> item.getName().equals("Image product"))
                .findFirst()
                .orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotNull(product.getImageFilename());
        mockMvc.perform(get("/images/{filename}", product.getImageFilename()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(MediaType.IMAGE_PNG));
    }
}
