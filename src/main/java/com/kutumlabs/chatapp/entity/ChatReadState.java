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
@Table("read_state_by_user")
public class ChatReadState {

    @PrimaryKey
    private ChatReadStateKey key;

    @Column("last_read_message_id")
    private Ulid lastReadMessageId;

    @Column("last_read_at")
    private Instant lastReadAt;
}
