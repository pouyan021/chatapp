plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
}

group = "com.kutumlabs"
version = "0.0.1-SNAPSHOT"
description = "chatapp"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

repositories {
    mavenCentral()
}

spotless {
    java {
        palantirJavaFormat()
    }
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    runtimeOnly(libs.spring.boot.docker.compose)
    runtimeOnly(libs.micrometer.prometheus)
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.s3)
    implementation(libs.spring.boot.security)
    implementation(libs.ulid.creator)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.cassandra)
    implementation(libs.spring.boot.opentelemetry)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.webmvc)
    implementation(libs.springdoc.webmvc.ui)
    implementation(libs.spring.boot.websocket)
    implementation(libs.spring.security.messaging)
    testImplementation(libs.spring.boot.test.actuator)
    testImplementation(libs.spring.boot.test.cassandra)
    testImplementation(libs.spring.boot.test.opentelemetry)
    testImplementation(libs.spring.boot.test.webmvc)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.cassandra)
    testImplementation(libs.testcontainers.grafana)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.processResources {
    from("docs/stomp.md") {
        into("dev-assets")
    }
}
