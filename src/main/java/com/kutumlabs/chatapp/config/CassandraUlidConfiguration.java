package com.kutumlabs.chatapp.config;

import com.github.f4b6a3.ulid.Ulid;
import java.nio.ByteBuffer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.cassandra.core.convert.CassandraCustomConversions;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

@Configuration(proxyBeanMethods = false)
public class CassandraUlidConfiguration {

    @Bean
    public CassandraCustomConversions cassandraCustomConversions() {
        return CassandraCustomConversions.create(
                adapter -> adapter.registerConverters(UlidWriteConverter.INSTANCE, UlidReadConverter.INSTANCE));
    }

    @WritingConverter
    enum UlidWriteConverter implements Converter<Ulid, ByteBuffer> {
        INSTANCE;

        @Override
        public ByteBuffer convert(Ulid source) {
            return ByteBuffer.wrap(source.toBytes());
        }
    }

    @ReadingConverter
    enum UlidReadConverter implements Converter<ByteBuffer, Ulid> {
        INSTANCE;

        @Override
        public Ulid convert(ByteBuffer source) {
            if (source.remaining() != 16) {
                throw new IllegalArgumentException("A ULID must contain exactly 16 bytes");
            }
            byte[] bytes = new byte[16];
            source.duplicate().get(bytes);
            return Ulid.from(bytes);
        }
    }
}
