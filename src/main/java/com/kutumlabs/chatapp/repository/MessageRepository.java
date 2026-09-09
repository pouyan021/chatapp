package com.kutumlabs.chatapp.repository;

import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.entity.Message;
import com.kutumlabs.chatapp.entity.MessageKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface MessageRepository extends CassandraRepository<Message, MessageKey> {

    Slice<Message> findByKeyChatId(Ulid chatId, Pageable pageable);
}
