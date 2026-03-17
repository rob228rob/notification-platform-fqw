//package ru.batoyan.vkr.config;
//
//import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
//import io.swagger.v3.oas.annotations.security.OAuthFlow;
//import io.swagger.v3.oas.annotations.security.OAuthFlows;
//import io.swagger.v3.oas.annotations.security.OAuthScope;
//import io.swagger.v3.oas.annotations.security.SecurityScheme;
//import io.swagger.v3.oas.models.OpenAPI;
//import io.swagger.v3.oas.models.info.Contact;
//import io.swagger.v3.oas.models.info.Info;
//import io.swagger.v3.oas.models.servers.Server;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import java.util.List;
//
///**
// * @author Batoyan Robert
// * @since 16.04.2025
// */
//@SecurityScheme(
//        name = "sso_auth",
//        type = SecuritySchemeType.OAUTH2,
//        flows = @OAuthFlows(password = @OAuthFlow(tokenUrl = "${keycloak.token-url}",
//                scopes = {
//                        @OAuthScope(name = "read", description = "Разрешение на чтение данных"),
//                        @OAuthScope(name = "write", description = "Разрешение на запись данных")
//                })))
//@Configuration
//public class OpenApiConfig {
//
//    @Value("http://localhost:${server.port}")
//    private String devUrl;
//
//    @Bean
//    public OpenAPI myOpenAPI() {
//        Server devServer = new Server();
//        devServer.setUrl(devUrl);
//        devServer.setDescription("Server URL in Development environment");
//
//        Contact contact = new Contact();
//        contact.setEmail("rlbatoyan@gmail.com");
//
//        Info info = new Info()
//                .title("Product API")
//                .version("1.0")
//                .contact(contact)
//                .description("This API exposes endpoints to manage products.");
//
//        return new OpenAPI().info(info).servers(List.of(devServer));
//    }
//}