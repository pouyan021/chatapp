package com.kutumlabs.chatapp.api;

import com.kutumlabs.chatapp.chat.ChatFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    public record ErrorBody(String code, String message, boolean retryable) {}

    @ExceptionHandler(ChatFailure.class)
    ResponseEntity<ErrorBody> chatFailure(ChatFailure error) {
        return ResponseEntity.status(error.status())
                .body(new ErrorBody(error.code(), error.getMessage(), error.retryable()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", message, false));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorBody> invalidParameter(HandlerMethodValidationException error) {
        String message = error.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", message, false));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> invalidInput() {
        return ResponseEntity.badRequest()
                .body(new ErrorBody("INVALID_REQUEST", "Invalid request body or parameters", false));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unavailable(Exception error, HttpServletRequest request) {
        log.atError().setCause(error).addKeyValue("event", "rest.failed").log("REST operation failed");
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(context -> context.setError(error));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorBody("TEMPORARILY_UNAVAILABLE", "The operation could not be completed", true));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorBody("NOT_FOUND", "Resource not found", false));
    }
}
