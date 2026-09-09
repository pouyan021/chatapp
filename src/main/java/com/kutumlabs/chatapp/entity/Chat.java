package com.kutumlabs.chatapp.entity;

import com.github.f4b6a3.ulid.Ulid;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("chats")
public class Chat {

    @PrimaryKey("chat_id")
    private Ulid chatId;

    @Column("name")
    private String name;

    @Column("type")
    private String type;

    @Column("created_at")
    private Instant createdAt;
}
