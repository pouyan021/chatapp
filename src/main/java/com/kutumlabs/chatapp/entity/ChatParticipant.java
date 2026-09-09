package com.kutumlabs.chatapp.entity;

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
@Table("participants_by_chat")
public class ChatParticipant {

    @PrimaryKey
    private ChatParticipantKey key;

    @Column("joined_at")
    private Instant joinedAt;

    @Column("role")
    private String role;
}
