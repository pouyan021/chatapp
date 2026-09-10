package com.kutumlabs.chatapp.entity;

import com.github.f4b6a3.ulid.Ulid;
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
@Table("messages_by_chat")
public class Message {

    @PrimaryKey
    private MessageKey key;

    @Column("sender_id")
    private Ulid senderId;

    @Column("content_type")
    private String contentType;

    @Column("body")
    private String body;

    @Column("media_bucket")
    private String mediaBucket;

    @Column("media_key")
    private String mediaKey;

    @Column("media_size_bytes")
    private Long mediaSizeBytes;

    @Column("media_version_id")
    private String mediaVersionId;
}
