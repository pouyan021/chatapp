# Table identifiers

ULID generation, parsing, and binary encoding use
[`ulid-creator`](https://github.com/f4b6a3/ulid-creator)
(`com.github.f4b6a3:ulid-creator:5.2.3`). There is no application-defined ULID
implementation.

Chat, user, message, and related reference IDs use `Ulid`. Generate IDs explicitly
with `UlidCreator.getMonotonicUlid()` before constructing an entity or key, and reuse the same ID in
every denormalized table. Saving an entity does not generate an ID. Device IDs
remain externally supplied strings.

```java
import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;

Ulid chatId = UlidCreator.getMonotonicUlid();
Chat chat = new Chat(chatId, "General", "GROUP", Instant.now());
chatRepository.save(chat);
chatRepository.findById(chatId);
String publicId = chatId.toString(); // 26-character Crockford Base32
Ulid parsed = Ulid.from(publicId);
```

`CassandraUlidConfiguration` converts ULIDs to/from big-endian, 16-byte CQL
`blob` values. This uses the same payload size as Cassandra's native UUID and
less than storing a 26-byte ULID string. Unsigned binary ordering matches ULID
time ordering. Existing timestamp clustering columns are retained because they
represent application timestamps, which can differ from ID generation times.

ULIDs contain a 48-bit millisecond timestamp and 80 bits of entropy. Use the
library's monotonic factory consistently when generation order matters. Independent
JVMs have no global monotonic ordering. ULIDs are identifiers, not authorization
tokens.

Cassandra hashes partition keys, so ULIDs do not make partitions sequential or
automatically improve partition lookup performance. Their advantage here is
sortable IDs while retaining a compact 128-bit representation.

## Local development database

Cassandra currently runs only in Docker for development; there is no live database
to migrate. `compose.yaml` initializes the local database from
`src/main/resources/schema.cql` and stores its data in the `cassandra-data` volume.
Integration tests initialize their own Cassandra container using the same schema.

When changing the schema, recreate the disposable local database if needed:
`CREATE TABLE IF NOT EXISTS` does not update existing tables, and restarting the
container preserves the data volume.
