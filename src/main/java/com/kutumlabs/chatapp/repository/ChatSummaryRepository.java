package com.kutumlabs.chatapp.repository;

import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.entity.ChatSummary;
import com.kutumlabs.chatapp.entity.ChatSummaryKey;
import java.util.List;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface ChatSummaryRepository extends CassandraRepository<ChatSummary, ChatSummaryKey> {

    List<ChatSummary> findByKeyUserId(Ulid userId);
}
