package com.kutumlabs.chatapp.media;

import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;
import com.kutumlabs.chatapp.chat.*;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import com.kutumlabs.chatapp.config.ChatProperties;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class MediaService {
    private final ChatStore store;
    private final MembershipService membership;
    private final ObjectStorage storage;
    private final ChatProperties properties;
    private final Clock clock;
    private final Validator validator;

    public MediaService(
            ChatStore store,
            MembershipService membership,
            ObjectStorage storage,
            ChatProperties properties,
            Clock clock,
            Validator validator) {
        this.store = store;
        this.membership = membership;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.validator = validator;
    }

    public ObjectStorage.SignedUpload create(Ulid user, Ulid chat, UploadRequest request) {
        ChatModels.validate(validator, request);
        if (request.sizeBytes() > properties.storage().maxUploadBytes()) {
            throw ChatFailure.invalid("Media exceeds the configured size limit");
        }
        Ulid uploadId = UlidCreator.getMonotonicUlid();
        var settings = properties.storage();
        var intent = new UploadIntent(
                uploadId,
                user,
                chat,
                settings.bucket(),
                chat + "/" + user + "/" + uploadId,
                request.sizeBytes(),
                request.contentType().toLowerCase(Locale.ROOT),
                clock.instant().plus(settings.intentTtl()));
        membership.require(chat, user);
        storage.requireVersioning();
        store.saveUpload(intent, Math.toIntExact(settings.intentTtl().toSeconds()));
        return storage.upload(intent);
    }

    public MediaObject verify(Ulid user, Ulid chat, MediaReference reference) {
        ChatModels.validate(validator, reference);
        Ulid uploadId = ChatModels.id(reference.uploadId());
        UploadIntent intent =
                store.findUpload(uploadId).orElseThrow(() -> ChatFailure.invalid("Upload expired or not found"));
        if (!intent.userId().equals(user) || !intent.chatId().equals(chat)) throw ChatFailure.forbidden();
        if (!intent.expiresAt().isAfter(clock.instant())) throw ChatFailure.invalid("Upload intent has expired");
        return storage.verify(intent, reference.versionId());
    }

    public ObjectStorage.SignedDownload download(Ulid user, Ulid chat, MessageLocator locator) {
        ChatModels.validate(validator, locator);
        Ulid id = ChatModels.id(locator.messageId());
        membership.require(chat, user);
        StoredMessage message = store.findMessage(chat, locator.createdAt(), id).orElseThrow(ChatFailure::missing);
        if (message.mediaBucket() == null) throw ChatFailure.missing();
        return storage.download(message);
    }
}
