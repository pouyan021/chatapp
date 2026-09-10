package com.kutumlabs.chatapp.media;

import com.kutumlabs.chatapp.chat.ChatFailure;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import com.kutumlabs.chatapp.config.ChatProperties;
import java.time.Clock;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

@Component
public class S3ObjectStorage implements ObjectStorage {
    private final S3Client client;
    private final S3Presigner presigner;
    private final ChatProperties properties;
    private final Clock clock;

    public S3ObjectStorage(S3Client client, S3Presigner presigner, ChatProperties properties, Clock clock) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void requireVersioning() {
        var result = client.getBucketVersioning(GetBucketVersioningRequest.builder()
                .bucket(properties.storage().bucket())
                .build());
        if (result.status() != BucketVersioningStatus.ENABLED) {
            throw new ChatFailure(
                    "STORAGE_NOT_READY",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Media bucket versioning must be enabled",
                    true);
        }
    }

    @Override
    public SignedUpload upload(UploadIntent intent) {
        var request = PutObjectRequest.builder()
                .bucket(intent.bucket())
                .key(intent.key())
                .contentLength(intent.sizeBytes())
                .contentType(intent.contentType())
                .build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(properties.storage().uploadUrlTtl())
                .putObjectRequest(request)
                .build());
        var headers = new LinkedHashMap<>(signed.signedHeaders());
        headers.remove("host");
        return new SignedUpload(
                intent.uploadId().toString(),
                signed.url().toString(),
                headers,
                clock.instant().plus(properties.storage().uploadUrlTtl()));
    }

    @Override
    public MediaObject verify(UploadIntent intent, String versionId) {
        try {
            var head = client.headObject(HeadObjectRequest.builder()
                    .bucket(intent.bucket())
                    .key(intent.key())
                    .versionId(versionId)
                    .build());
            if (!versionId.equals(head.versionId())
                    || head.contentLength() != intent.sizeBytes()
                    || !intent.contentType().equals(head.contentType())) {
                throw ChatFailure.invalid("Uploaded object size, content type or version does not match");
            }
            return new MediaObject(intent.bucket(), intent.key(), versionId, head.contentLength(), head.contentType());
        } catch (S3Exception error) {
            if (error.statusCode() == 404) throw ChatFailure.invalid("Uploaded object version not found");
            throw error;
        }
    }

    @Override
    public SignedDownload download(StoredMessage message) {
        var request = GetObjectRequest.builder()
                .bucket(message.mediaBucket())
                .key(message.mediaKey())
                .versionId(message.mediaVersionId())
                .responseContentDisposition("attachment")
                .responseContentType("application/octet-stream")
                .build();
        var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(properties.storage().downloadUrlTtl())
                .getObjectRequest(request)
                .build());
        return new SignedDownload(
                signed.url().toString(),
                clock.instant().plus(properties.storage().downloadUrlTtl()));
    }
}
