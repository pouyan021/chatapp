package com.kutumlabs.chatapp.repository;

import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.entity.Chat;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface ChatRepository extends CassandraRepository<Chat, Ulid> {}
