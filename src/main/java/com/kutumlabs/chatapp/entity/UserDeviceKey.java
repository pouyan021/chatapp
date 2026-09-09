package com.kutumlabs.chatapp.entity;

import com.github.f4b6a3.ulid.Ulid;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrimaryKeyClass
public class UserDeviceKey implements Serializable {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private Ulid userId;

    @PrimaryKeyColumn(name = "device_id", type = PrimaryKeyType.CLUSTERED)
    private String deviceId;
}
