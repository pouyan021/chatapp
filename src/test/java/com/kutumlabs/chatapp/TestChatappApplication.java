package com.kutumlabs.chatapp;

import org.springframework.boot.SpringApplication;

public class TestChatappApplication {

    public static void main(String[] args) {
        SpringApplication.from(ChatappApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
