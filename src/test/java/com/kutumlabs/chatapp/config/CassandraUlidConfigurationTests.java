package com.kutumlabs.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;
import com.kutumlabs.chatapp.entity.Chat;
import com.kutumlabs.chatapp.entity.ChatReadState;
import com.kutumlabs.chatapp.entity.ChatReadStateKey;
import com.kutumlabs.chatapp.entity.Message;
import com.kutumlabs.chatapp.entity.MessageKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.cassandra.core.convert.MappingCassandraConverter;
import org.springframework.data.cassandra.core.convert.QueryMapper;
import org.springframework.data.cassandra.core.mapping.CassandraMappingContext;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Filter;

class CassandraUlidConfigurationTests {

    @Test
    void mapsSimpleKeysCompositeKeysAndReferencesToBlobs() {
        MappingCassandraConverter converter = converter();
        Ulid chatId = UlidCreator.getMonotonicUlid();
        Ulid userId = UlidCreator.getMonotonicUlid();
        Ulid messageId = UlidCreator.getMonotonicUlid();
        Instant now = Instant.now();

        Map<CqlIdentifier, Object> chat = write(converter, new Chat(chatId, "General", "GROUP", now));
        assertThat(chat).containsEntry(CqlIdentifier.fromCql("chat_id"), ByteBuffer.wrap(chatId.toBytes()));

        Message message =
                new Message(new MessageKey(chatId, now, messageId), userId, "text", "Hello", null, null, null, null);
        Map<CqlIdentifier, Object> columns = write(converter, message);
        assertThat(columns)
                .containsEntry(CqlIdentifier.fromCql("chat_id"), ByteBuffer.wrap(chatId.toBytes()))
                .containsEntry(CqlIdentifier.fromCql("message_id"), ByteBuffer.wrap(messageId.toBytes()))
                .containsEntry(CqlIdentifier.fromCql("sender_id"), ByteBuffer.wrap(userId.toBytes()));

        Map<CqlIdentifier, Object> readState =
                write(converter, new ChatReadState(new ChatReadStateKey(userId, chatId), messageId, now));
        assertThat(readState)
                .containsEntry(CqlIdentifier.fromCql("last_read_message_id"), ByteBuffer.wrap(messageId.toBytes()));

        var property = converter
                .getMappingContext()
                .getRequiredPersistentEntity(Chat.class)
                .getRequiredPersistentProperty("chatId");
        assertThat(converter.getColumnTypeResolver().resolve(property).getDataType())
                .isEqualTo(DataTypes.BLOB);
        assertThat(converter.convertToColumnType(chatId)).isEqualTo(ByteBuffer.wrap(chatId.toBytes()));
    }

    @Test
    void registersBinaryReadAndWriteConversions() {
        var service = converter().getConversionService();
        Ulid id = UlidCreator.getMonotonicUlid();
        ByteBuffer stored = service.convert(id, ByteBuffer.class);

        assertThat(stored).isNotNull();
        assertThat(stored.remaining()).isEqualTo(16);
        assertThat(service.convert(stored, Ulid.class)).isEqualTo(id);
        assertThat(stored.position()).isZero();
    }

    @Test
    void preservesTheExistingBinaryStorageFormat() {
        Ulid id = Ulid.from("01ARZ3NDEKTSV4RRFFQ69G5FAV");
        ByteBuffer bytes = CassandraUlidConfiguration.UlidWriteConverter.INSTANCE.convert(id);

        assertThat(HexFormat.of().formatHex(bytes.array())).isEqualTo("01563e3ab5d3d6764c61efb99302bd5b");
        assertThat(CassandraUlidConfiguration.UlidReadConverter.INSTANCE.convert(bytes))
                .isEqualTo(id);
    }

    @Test
    void readsOnlyTheBufferWindowWithoutChangingItsPosition() {
        Ulid id = UlidCreator.getMonotonicUlid();
        ByteBuffer buffer = ByteBuffer.allocateDirect(20);
        buffer.position(2);
        buffer.put(id.toBytes());
        buffer.flip().position(2);
        ByteBuffer readOnly = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);

        assertThat(CassandraUlidConfiguration.UlidReadConverter.INSTANCE.convert(readOnly))
                .isEqualTo(id);
        assertThat(readOnly.position()).isEqualTo(2);
        assertThat(readOnly.limit()).isEqualTo(18);
    }

    @Test
    void rejectsBlobsWithIncorrectLengths() {
        for (int length : new int[] {0, 15, 17}) {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CassandraUlidConfiguration.UlidReadConverter.INSTANCE.convert(buffer));
        }
    }

    @Test
    void convertsLookupParametersForSimpleAndCompositeKeys() {
        var converter = converter();
        var mapper = new QueryMapper(converter);
        Ulid id = UlidCreator.getMonotonicUlid();

        var simple = mapper.getMappedObject(
                Filter.from(Criteria.where("chatId").is(id)),
                converter.getMappingContext().getRequiredPersistentEntity(Chat.class));
        var composite = mapper.getMappedObject(
                Filter.from(Criteria.where("key.chatId").is(id)),
                converter.getMappingContext().getRequiredPersistentEntity(Message.class));

        for (var mapped : new Filter[] {simple, composite}) {
            var criterion = mapped.iterator().next();
            assertThat(criterion.getColumnName().getRequiredCqlIdentifier())
                    .isEqualTo(CqlIdentifier.fromCql("chat_id"));
            assertThat(criterion.getPredicate().getValue()).isEqualTo(ByteBuffer.wrap(id.toBytes()));
        }
    }

    private MappingCassandraConverter converter() {
        var conversions = new CassandraUlidConfiguration().cassandraCustomConversions();
        CassandraMappingContext context = new CassandraMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingCassandraConverter converter = new MappingCassandraConverter(context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return converter;
    }

    private Map<CqlIdentifier, Object> write(MappingCassandraConverter converter, Object entity) {
        Map<CqlIdentifier, Object> columns = new LinkedHashMap<>();
        converter.write(entity, columns);
        return columns;
    }
}
