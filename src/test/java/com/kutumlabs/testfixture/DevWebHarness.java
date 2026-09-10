package com.kutumlabs.testfixture;

import com.kutumlabs.chatapp.api.ApiErrors;
import com.kutumlabs.chatapp.api.ChatController;
import com.kutumlabs.chatapp.chat.ChatService;
import com.kutumlabs.chatapp.config.SecurityConfiguration;
import com.kutumlabs.chatapp.dev.DevConfiguration;
import com.kutumlabs.chatapp.dev.DevController;
import com.kutumlabs.chatapp.media.MediaService;
import com.kutumlabs.chatapp.socket.ConnectionRegistry;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@MockitoBean(types = {ChatService.class, MediaService.class, ConnectionRegistry.class})
public abstract class DevWebHarness {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = CassandraAutoConfiguration.class)
    @Import({
        SecurityConfiguration.class,
        DevConfiguration.class,
        DevController.class,
        ChatController.class,
        ApiErrors.class
    })
    public static class Application {}
}
