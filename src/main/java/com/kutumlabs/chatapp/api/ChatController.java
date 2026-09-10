package com.kutumlabs.chatapp.api;

import com.kutumlabs.chatapp.chat.*;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import com.kutumlabs.chatapp.media.MediaService;
import com.kutumlabs.chatapp.media.ObjectStorage;
import com.kutumlabs.chatapp.socket.ConnectionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Chat API")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer JWT", content = @Content),
    @ApiResponse(
            responseCode = "503",
            description = "Temporarily unavailable",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
})
public class ChatController {

    public static final String USER_ID = "user_id";
    private final ChatService chats;
    private final MediaService media;
    private final ConnectionRegistry connections;

    public ChatController(ChatService chats, MediaService media, ConnectionRegistry connections) {
        this.chats = chats;
        this.media = media;
        this.connections = connections;
    }

    @Operation(
            summary = "Create a chat",
            description =
                    "The caller is automatically included. DIRECT needs exactly two distinct members; GROUP accepts 2–100.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @PostMapping("/chats")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatView create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateChat request) {
        return chats.create(ChatModels.id(jwt.getClaimAsString(USER_ID)), request);
    }

    @Operation(summary = "List your chats", description = "Returns up to 50 chat summaries.")
    @GetMapping("/chats")
    public List<ChatSummaryView> listChats(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return chats.listChats(ChatModels.id(jwt.getClaimAsString(USER_ID)));
    }

    @Operation(
            summary = "Mark a chat read",
            description = "Records the supplied message ULID as the read position. Requires membership.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Chat membership is required",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @PostMapping("/chats/{chatId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Chat ULID returned by create/list", example = "01ARZ3NDEKTSV4RRFFQ69G5FAW")
                    @PathVariable
                    String chatId,
            @Valid @RequestBody MarkReadCommand request) {
        chats.markRead(ChatModels.id(jwt.getClaimAsString(USER_ID)), ChatModels.id(chatId), request);
    }

    @Operation(
            summary = "Register a device push token",
            description = "Stores a token for the authenticated user. This does not send push notifications.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @PutMapping("/devices/{deviceId}/push-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerDevice(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String deviceId,
            @Valid @RequestBody RegisterDevice request) {
        chats.registerDevice(ChatModels.id(jwt.getClaimAsString(USER_ID)), deviceId, request);
    }

    @Operation(
            summary = "Read message history",
            description =
                    "Newest first. Pass nextCursor unchanged to fetch older messages, keeping chatId and limit unchanged. Null nextCursor means the end. Requires membership.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Chat membership is required",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @GetMapping("/chats/{chatId}/messages")
    public History history(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Chat ULID returned by create/list", example = "01ARZ3NDEKTSV4RRFFQ69G5FAW")
                    @PathVariable
                    String chatId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) {
        return chats.history(ChatModels.id(jwt.getClaimAsString(USER_ID)), ChatModels.id(chatId), limit, cursor);
    }

    @Operation(
            summary = "Create a signed media upload",
            description =
                    "Requires membership. PUT bytes directly to the returned URL with its supplied headers. Capture the object version ID and send uploadId plus versionId through STOMP.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Chat membership is required",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @ApiResponse(
            responseCode = "201",
            description = "Signed upload created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ObjectStorage.SignedUpload.class)))
    @PostMapping("/chats/{chatId}/uploads")
    public ResponseEntity<ObjectStorage.SignedUpload> upload(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Chat ULID returned by create/list", example = "01ARZ3NDEKTSV4RRFFQ69G5FAW")
                    @PathVariable
                    String chatId,
            @Valid @RequestBody UploadRequest request) {
        var result = media.create(ChatModels.id(jwt.getClaimAsString(USER_ID)), ChatModels.id(chatId), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    @Operation(
            summary = "Create a signed media download URL",
            description =
                    "Requires membership and a media message. Use messageId and createdAt from history or a live event.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Chat membership is required",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Resource not found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @PostMapping("/chats/{chatId}/media/download-url")
    public ResponseEntity<ObjectStorage.SignedDownload> download(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Chat ULID returned by create/list", example = "01ARZ3NDEKTSV4RRFFQ69G5FAW")
                    @PathVariable
                    String chatId,
            @Valid @RequestBody MessageLocator request) {
        var result = media.download(ChatModels.id(jwt.getClaimAsString(USER_ID)), ChatModels.id(chatId), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @Operation(
            summary = "List your live connections",
            description = "Lists connections for the authenticated user on this server instance.")
    @GetMapping("/connections")
    public List<ConnectionRegistry.SessionView> connections(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return connections.list(ChatModels.id(jwt.getClaimAsString(USER_ID)));
    }

    @Operation(
            summary = "Disconnect one of your sessions",
            description =
                    "Closes only a session owned by the authenticated user. Returns 404 when absent or owned by another user.")
    @ApiResponse(
            responseCode = "404",
            description = "Resource not found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    @DeleteMapping("/connections/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @PathVariable String sessionId) {
        if (!connections.disconnect(ChatModels.id(jwt.getClaimAsString(USER_ID)), sessionId)) {
            throw ChatFailure.missing();
        }
    }
}
