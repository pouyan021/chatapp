package com.kutumlabs.chatapp.repository;

import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.entity.ChatParticipant;
import com.kutumlabs.chatapp.entity.ChatParticipantKey;
import java.util.List;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface ChatParticipantRepository extends CassandraRepository<ChatParticipant, ChatParticipantKey> {

    List<ChatParticipant> findByKeyChatId(Ulid chatId);
}
