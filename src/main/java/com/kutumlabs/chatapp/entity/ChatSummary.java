package com.kutumlabs.chatapp.entity;

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
@Table("chats_by_user")
public class ChatSummary {

    @PrimaryKey
    private ChatSummaryKey key;

    @Column("chat_name")
    private String chatName;

    @Column("last_message_preview")
    private String lastMessagePreview;
}
