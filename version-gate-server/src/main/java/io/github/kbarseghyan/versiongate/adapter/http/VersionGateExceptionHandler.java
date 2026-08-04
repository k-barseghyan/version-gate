package io.github.kbarseghyan.versiongate.adapter.http;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
final class VersionGateExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(VersionGateExceptionHandler.class);
  private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  @ExceptionHandler(VersionGateException.class)
  ResponseEntity<ProblemDetail> handleVersionGateException(
      VersionGateException exception, HttpServletRequest request) {
    HttpStatus status = statusFor(exception.code());
    if (exception.code() == ErrorCode.STORAGE_FAILURE) {
      String correlationId = UUID.randomUUID().toString();
      LOGGER.error("Storage dependency failure; correlationId={}", correlationId, exception);
      ProblemDetail problem =
          problem(
              status,
              exception.code().name(),
              "A storage dependency could not complete the request",
              request);
      problem.setProperty("correlationId", correlationId);
      return ResponseEntity.status(status).body(problem);
    }
    ProblemDetail problem =
        problem(status, exception.code().name(), exception.getMessage(), request);
    exception.details().forEach(problem::setProperty);
    return ResponseEntity.status(status).body(problem);
  }

  @ExceptionHandler(HttpApiException.class)
  ResponseEntity<ProblemDetail> handleHttpApiException(
      HttpApiException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(exception.status(), exception.code(), exception.getMessage(), request);
    return ResponseEntity.status(exception.status()).body(problem);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    for (FieldError error : exception.getBindingResult().getFieldErrors()) {
      errors.add(Map.of("field", error.getField(), "message", defaultMessage(error)));
    }
    for (ObjectError error : exception.getBindingResult().getGlobalErrors()) {
      errors.add(Map.of("field", error.getObjectName(), "message", defaultMessage(error)));
    }
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed", request);
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler({
    MissingRequestHeaderException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    HandlerMethodValidationException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ProblemDetail> handleInvalidRequest(
      Exception exception, HttpServletRequest request) {
    String detail =
        exception instanceof MissingRequestHeaderException missingHeader
            ? "Required header " + missingHeader.getHeaderName() + " is missing"
            : "Request validation failed";
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, detail, request);
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "UNSUPPORTED_CONTENT_TYPE",
            "Request Content-Type is not supported",
            request);
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(problem);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
    LOGGER.error("Unhandled request failure", exception);
    ProblemDetail problem =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            request);
    return ResponseEntity.internalServerError().body(problem);
  }

  private static HttpStatus statusFor(ErrorCode code) {
    return switch (code) {
      case RESOURCE_NOT_FOUND,
          ACTIVE_VERSION_NOT_FOUND,
          WRITE_SESSION_NOT_FOUND,
          LIVE_READ_SESSION_NOT_FOUND,
          SNAPSHOT_SESSION_NOT_FOUND,
          SNAPSHOT_NOT_FOUND,
          CURRENT_SNAPSHOT_UNAVAILABLE ->
          HttpStatus.NOT_FOUND;
      case RESOURCE_ALREADY_EXISTS,
          WRITE_ALREADY_ACTIVE,
          LIVE_READ_ACTIVE,
          SNAPSHOT_SESSION_ALREADY_EXISTS,
          SNAPSHOT_GENERATION_ACTIVE,
          SNAPSHOT_SUPPORT_DISABLED,
          CURRENT_SNAPSHOT_REQUIRED,
          SNAPSHOT_INVALIDATED,
          WRITE_IN_PROGRESS,
          SNAPSHOT_CONFLICT,
          LEASE_EXPIRED,
          INVALID_SESSION_TRANSITION ->
          HttpStatus.CONFLICT;
      case STALE_FENCING_TOKEN -> HttpStatus.PRECONDITION_FAILED;
      case CHECKSUM_MISMATCH -> HttpStatus.UNPROCESSABLE_CONTENT;
      case STORAGE_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
      case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
    };
  }

  private static ProblemDetail problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(
        URI.create("urn:version-gate:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
    problem.setTitle(title(code));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    return problem;
  }

  private static String title(String code) {
    String[] words = code.toLowerCase(Locale.ROOT).split("_");
    StringBuilder title = new StringBuilder();
    for (String word : words) {
      if (!title.isEmpty()) {
        title.append(' ');
      }
      title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return title.toString();
  }

  private static String defaultMessage(ObjectError error) {
    return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
  }
}
