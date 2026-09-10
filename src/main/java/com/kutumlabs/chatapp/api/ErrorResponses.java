package com.kutumlabs.chatapp.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Endpoint-specific HTTP error codes for generated documentation; does not affect request handling. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ErrorResponses {
    int[] value();
}
