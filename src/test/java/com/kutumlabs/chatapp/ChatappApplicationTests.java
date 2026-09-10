package com.kutumlabs.chatapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatappApplicationTests {

    @Autowired
    private CqlSession session;

    @Test
    void contextLoads() {
        assertThat(session.getKeyspace()).contains(CqlIdentifier.fromCql("chatapp"));
        var tables =
                session
                        .execute("SELECT table_name FROM system_schema.tables WHERE keyspace_name = 'chatapp'")
                        .all()
                        .stream()
                        .map(row -> row.getString("table_name"))
                        .toList();
        assertThat(tables)
                .contains(
                        "chats",
                        "messages_by_chat",
                        "chats_by_user",
                        "participants_by_chat",
                        "read_state_by_user",
                        "devices_by_user");
    }
}
