package com.kutumlabs.chatapp.entity;

import com.github.f4b6a3.ulid.Ulid;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrimaryKeyClass
public class MessageKey implements Serializable {

    @PrimaryKeyColumn(name = "chat_id", type = PrimaryKeyType.PARTITIONED)
    private Ulid chatId;

    @PrimaryKeyColumn(name = "created_at", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "message_id", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Ulid messageId;
}
