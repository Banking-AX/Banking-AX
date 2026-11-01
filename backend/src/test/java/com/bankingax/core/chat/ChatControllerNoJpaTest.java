package com.bankingax.core.chat;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.auth.TaskBffTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatController.class)
@Import({
    ChatService.class,
    ChatExceptionHandler.class,
    TaskBffPrincipal.class,
    TaskBffTestSecurityConfiguration.class
})
class ChatControllerNoJpaTest {
    @Autowired
    MockMvc http;

    @Test
    void reportsServiceUnavailableWhenChatPersistenceIsNotConfigured() throws Exception {
        http.perform(post("/api/v1/customer/chat/rooms")
                        .with(jwt().jwt(token -> token.subject("1").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CHAT_PERSISTENCE_UNAVAILABLE"));
    }
}
