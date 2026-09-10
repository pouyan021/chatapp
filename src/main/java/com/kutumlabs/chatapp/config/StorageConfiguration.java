package com.kutumlabs.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class StorageConfiguration {
    @Bean
    AwsCredentialsProvider storageCredentials(ChatProperties properties) {
        var storage = properties.storage();
        if (storage.accessKey() == null || storage.accessKey().isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(ChatProperties properties, AwsCredentialsProvider credentials) {
        var storage = properties.storage();
        return S3Client.builder()
                .endpointOverride(storage.endpoint())
                .credentialsProvider(credentials)
                .region(Region.of(storage.region()))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(ChatProperties properties, AwsCredentialsProvider credentials) {
        var storage = properties.storage();
        return S3Presigner.builder()
                .endpointOverride(storage.publicEndpoint())
                .credentialsProvider(credentials)
                .region(Region.of(storage.region()))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
