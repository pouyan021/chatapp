package com.kutumlabs.chatapp.media;

import com.kutumlabs.chatapp.chat.ChatModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ObjectStorage {
    record SignedUpload(String uploadId, String url, Map<String, List<String>> headers, Instant expiresAt) {}

    record SignedDownload(String url, Instant expiresAt) {}

    void requireVersioning();

    SignedUpload upload(UploadIntent intent);

    MediaObject verify(UploadIntent intent, String versionId);

    SignedDownload download(StoredMessage message);
}
