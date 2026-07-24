package io.github.kbarseghyan.versiongate.adapter.http;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.configuration.VersionGateProperties;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.port.ParticipantGateway;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

final class HttpParticipantGateway implements ParticipantGateway {

  private final HttpClient httpClient;
  private final JsonMapper jsonMapper;
  private final Duration requestTimeout;
  private final Set<String> allowedBaseUris;
  private final boolean allowHttp;

  HttpParticipantGateway(
      HttpClient participantHttpClient, JsonMapper jsonMapper, VersionGateProperties properties) {
    this.httpClient =
        java.util.Objects.requireNonNull(
            participantHttpClient, "participantHttpClient is required");
    if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException(
          "participantHttpClient must disable redirects to preserve the callback allowlist");
    }
    this.jsonMapper = java.util.Objects.requireNonNull(jsonMapper, "jsonMapper is required");
    this.requestTimeout = properties.participant().requestTimeout();
    this.allowedBaseUris =
        properties.participant().allowedBaseUris().stream()
            .map(HttpParticipantGateway::normalizeConfiguredBaseUri)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    this.allowHttp = properties.participant().allowHttp();
  }

  @Override
  public void validateRegistration(Participant participant) {
    validateDestination(participant, ErrorCode.VALIDATION_FAILED);
  }

  @Override
  public void quiesce(Participant participant, CallbackContext context) {
    post(participant, "quiesce", context.build());
  }

  @Override
  public void capture(Participant participant, CallbackContext context) {
    post(participant, "capture", context.build());
  }

  @Override
  public void resume(Participant participant, CallbackContext context) {
    post(participant, "resume", context.build());
  }

  @Override
  public void abort(Participant participant, CallbackContext context) {
    post(participant, "abort", context.build());
  }

  private void post(Participant participant, String operation, Build build) {
    String baseUri = validateDestination(participant, ErrorCode.PARTICIPANT_FAILURE);
    String actionId =
        UUID.nameUUIDFromBytes(
                (build.buildId() + ":" + participant.participantId() + ":" + operation)
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    URI uri = URI.create(baseUri + "/version-control/" + operation);
    try {
      String body =
          jsonMapper.writeValueAsString(
              new CallbackRequest(
                  1,
                  actionId,
                  participant.participantId(),
                  build.buildId().toString(),
                  build.resourceId(),
                  build.targetVersion(),
                  build.baseActiveVersion(),
                  build.fencingToken(),
                  build.leaseExpiresAt().toString()));
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(requestTimeout)
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", actionId)
              .header("X-Version-Gate-Protocol-Version", "1")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw participantFailure(
            participant, operation, "callback returned HTTP " + response.statusCode(), null);
      }
    } catch (tools.jackson.core.JacksonException exception) {
      throw participantFailure(
          participant, operation, "callback request could not be encoded", exception);
    } catch (IOException exception) {
      throw participantFailure(
          participant, operation, "callback could not be completed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw participantFailure(participant, operation, "callback was interrupted", exception);
    }
  }

  private String validateDestination(Participant participant, ErrorCode errorCode) {
    String normalizedBaseUri;
    try {
      normalizedBaseUri = normalizeBaseUri(participant.baseUri());
    } catch (IllegalArgumentException exception) {
      throw new VersionGateException(
          errorCode, "Participant base URI is not safe for callbacks", exception);
    }
    if (!allowedBaseUris.contains(normalizedBaseUri)) {
      throw new VersionGateException(
          errorCode,
          "Participant base URI "
              + normalizedBaseUri
              + " is not in the outbound callback allowlist");
    }
    if (!allowHttp && !"https".equalsIgnoreCase(participant.baseUri().getScheme())) {
      throw new VersionGateException(
          errorCode, "Participant callbacks require HTTPS unless HTTP is explicitly enabled");
    }
    return normalizedBaseUri;
  }

  private static String normalizeConfiguredBaseUri(String value) {
    try {
      return normalizeBaseUri(URI.create(value));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "version-gate.participant.allowed-base-uris contains an invalid URI", exception);
    }
  }

  private static String normalizeBaseUri(URI value) {
    URI normalized = value.normalize();
    String scheme = normalized.getScheme();
    String host = normalized.getHost();
    if (!normalized.isAbsolute()
        || scheme == null
        || host == null
        || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        || normalized.getUserInfo() != null
        || normalized.getQuery() != null
        || normalized.getFragment() != null) {
      throw new IllegalArgumentException(
          "callback base URI must be an absolute HTTP(S) URI without user info,"
              + " query, or fragment");
    }
    String rawPath = Optional.ofNullable(normalized.getRawPath()).orElse("");
    String lowerPath = rawPath.toLowerCase(Locale.ROOT);
    if (rawPath.indexOf('\\') >= 0
        || lowerPath.contains("%2e")
        || lowerPath.contains("%2f")
        || lowerPath.contains("%5c")) {
      throw new IllegalArgumentException(
          "callback base URI path must not contain encoded path separators or dots");
    }
    while (rawPath.endsWith("/") && !rawPath.isEmpty()) {
      rawPath = rawPath.substring(0, rawPath.length() - 1);
    }
    int port = normalized.getPort();
    if (("https".equalsIgnoreCase(scheme) && port == 443)
        || ("http".equalsIgnoreCase(scheme) && port == 80)) {
      port = -1;
    }
    String canonicalHost =
        host.indexOf(':') >= 0 && !host.startsWith("[")
            ? "[" + host.toLowerCase(Locale.ROOT) + "]"
            : host.toLowerCase(Locale.ROOT);
    String canonical =
        scheme.toLowerCase(Locale.ROOT)
            + "://"
            + canonicalHost
            + (port < 0 ? "" : ":" + port)
            + rawPath;
    return URI.create(canonical).toASCIIString();
  }

  private static VersionGateException participantFailure(
      Participant participant, String operation, String message, Exception cause) {
    return new VersionGateException(
        ErrorCode.PARTICIPANT_FAILURE,
        "Participant " + participant.participantId() + " " + operation + " " + message,
        cause);
  }

  private record CallbackRequest(
      int protocolVersion,
      String actionId,
      String participantId,
      String buildId,
      String resourceId,
      long targetVersion,
      Long baseActiveVersion,
      long fencingToken,
      String leaseExpiresAt) {}
}
