package com.kutumlabs.chatapp.repository;

import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.entity.ChatReadState;
import com.kutumlabs.chatapp.entity.ChatReadStateKey;
import java.util.List;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface ChatReadStateRepository extends CassandraRepository<ChatReadState, ChatReadStateKey> {

    List<ChatReadState> findByKeyUserId(Ulid userId);
}
