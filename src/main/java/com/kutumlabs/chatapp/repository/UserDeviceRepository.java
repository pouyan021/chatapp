package com.kutumlabs.chatapp.repository;

import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.entity.UserDevice;
import com.kutumlabs.chatapp.entity.UserDeviceKey;
import java.util.List;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface UserDeviceRepository extends CassandraRepository<UserDevice, UserDeviceKey> {

    List<UserDevice> findByKeyUserId(Ulid userId);
}
