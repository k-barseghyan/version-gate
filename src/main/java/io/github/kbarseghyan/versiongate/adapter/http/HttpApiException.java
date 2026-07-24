package io.github.kbarseghyan.versiongate.adapter.http;

import java.util.Objects;
import org.springframework.http.HttpStatus;

final class HttpApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  HttpApiException(HttpStatus status, String code, String detail) {
    super(detail);
    this.status = Objects.requireNonNull(status, "status is required");
    this.code = Objects.requireNonNull(code, "code is required");
  }

  HttpStatus status() {
    return status;
  }

  String code() {
    return code;
  }
}
