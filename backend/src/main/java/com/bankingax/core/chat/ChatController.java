package com.bankingax.core.chat;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/chat/rooms")
public class ChatController {
    private static final String TASK_CODE = "CHAT";

    private final ChatService chats;
    private final TaskBffPrincipal principals;

    public ChatController(ChatService chats, TaskBffPrincipal principals) {
        this.chats = chats;
        this.principals = principals;
    }

    @PostMapping
    public ResponseEntity<ApiEnvelope<ChatService.ChatRoomView>> createRoom(
            JwtAuthenticationToken authentication) {
        ChatService.ChatRoomView room = chats.createRoom(customerId(authentication));
        return ResponseEntity.created(URI.create("/api/v1/customer/chat/rooms/" + room.id()))
                .body(ApiEnvelope.success(null, TASK_CODE, null, null, null, room));
    }

    @GetMapping
    public ApiEnvelope<List<ChatService.ChatRoomView>> listRooms(
            JwtAuthenticationToken authentication) {
        return ApiEnvelope.success(
                null, TASK_CODE, null, null, null, chats.listRooms(customerId(authentication)));
    }

    @GetMapping("/{roomId}")
    public ApiEnvelope<ChatService.ChatRoomView> getRoom(
            JwtAuthenticationToken authentication, @PathVariable long roomId) {
        return ApiEnvelope.success(
                null, TASK_CODE, null, null, null, chats.getRoom(customerId(authentication), roomId));
    }

    @GetMapping("/{roomId}/messages")
    public ApiEnvelope<List<ChatService.ChatMessageView>> listMessages(
            JwtAuthenticationToken authentication, @PathVariable long roomId) {
        return ApiEnvelope.success(
                null, TASK_CODE, null, null, null, chats.listMessages(customerId(authentication), roomId));
    }

    @PostMapping("/{roomId}/messages")
    public ApiEnvelope<ChatService.ChatMessageView> saveMessage(
            JwtAuthenticationToken authentication,
            @PathVariable long roomId,
            @RequestBody MessageRequest request) {
        long customerId = customerId(authentication);
        ChatService.ChatMessageView message = chats.saveCustomerMessage(
                customerId, roomId, request.requestId(), request.content());
        return ApiEnvelope.success(request.requestId(), TASK_CODE, null, null, null, message);
    }

    private long customerId(JwtAuthenticationToken authentication) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role"))) {
            throw new DomainException("FORBIDDEN", "Customer access is required.");
        }
        return principals.customer(authentication);
    }

    public record MessageRequest(String requestId, String content) { }
}
