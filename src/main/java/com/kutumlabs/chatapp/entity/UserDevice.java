package com.kutumlabs.chatapp.entity;

import java.time.Instant;
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
@Table("devices_by_user")
public class UserDevice {

    @PrimaryKey
    private UserDeviceKey key;

    @Column("push_token")
    private String pushToken;

    @Column("last_seen_at")
    private Instant lastSeenAt;
}
