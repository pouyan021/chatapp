package com.kutumlabs.chatapp.dev;

import com.kutumlabs.chatapp.api.ApiErrors;
import com.kutumlabs.chatapp.api.ChatController;
import com.kutumlabs.chatapp.api.ErrorResponses;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevConfiguration implements WebMvcConfigurer {
    private static final Map<Integer, String> ERROR_DESCRIPTIONS = Map.of(
            400, "Invalid request",
            401, "Missing, expired, or invalid bearer JWT",
            403, "Chat membership is required",
            404, "Resource not found",
            503, "Temporarily unavailable");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/dev/assets/**").addResourceLocations("classpath:/dev-assets/");
    }

    @Bean
    OpenAPI chatOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Chat API").version("v1").description("""
                        Create chats and inspect persisted history here. Send and receive messages using
                        STOMP over WebSocket in the [chat playground](/dev/chat).
                        See the [STOMP protocol](/dev/protocol) for destinations, replies, and retry rules.
                        In the playground select Alice or Bob and click Copy token, then paste it into Authorize.
                        IDs are 26-character ULIDs; example IDs must be replaced with IDs returned by your server.
                        """))
                .components(chatComponents())
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    private Components chatComponents() {
        var components = new Components()
                .addSecuritySchemes(
                        "bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"));
        ModelConverters.getInstance().read(ApiErrors.ErrorBody.class).forEach(components::addSchemas);
        ERROR_DESCRIPTIONS.forEach((code, description) -> {
            var response = new ApiResponse().description(description);
            if (code != 401) {
                response.content(new Content()
                        .addMediaType(
                                "application/json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorBody"))));
            }
            components.addResponses("Error" + code, response);
        });
        return components;
    }

    @Bean
    OperationCustomizer chatOperationDocumentation() {
        return (operation, handler) -> {
            if (!ChatController.class.isAssignableFrom(handler.getBeanType())) return operation;
            operation.getResponses().addApiResponse("401", errorReference(401));
            operation.getResponses().addApiResponse("503", errorReference(503));
            var errors = handler.getMethodAnnotation(ErrorResponses.class);
            if (errors != null) {
                for (int code : errors.value()) {
                    operation.getResponses().addApiResponse(Integer.toString(code), errorReference(code));
                }
            }
            if (operation.getParameters() != null) {
                operation.getParameters().stream()
                        .filter(parameter -> "path".equals(parameter.getIn()) && "chatId".equals(parameter.getName()))
                        .forEach(parameter -> parameter
                                .description("Chat ULID returned by create/list")
                                .example("01ARZ3NDEKTSV4RRFFQ69G5FAW"));
            }
            return operation;
        };
    }

    private ApiResponse errorReference(int code) {
        if (!ERROR_DESCRIPTIONS.containsKey(code)) {
            throw new IllegalArgumentException("No shared documentation for HTTP error " + code);
        }
        return new ApiResponse().$ref("#/components/responses/Error" + code);
    }
}
