package io.github.kbarseghyan.versiongate.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kbarseghyan.versiongate.api.ErrorCode;
import io.github.kbarseghyan.versiongate.api.VersionGateException;
import io.github.kbarseghyan.versiongate.configuration.VersionGateProperties;
import io.github.kbarseghyan.versiongate.domain.Build;
import io.github.kbarseghyan.versiongate.domain.BuildStatus;
import io.github.kbarseghyan.versiongate.domain.Participant;
import io.github.kbarseghyan.versiongate.port.ParticipantGateway;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

class HttpParticipantGatewayTest {

  @Test
  void registrationRequiresTheExactNormalizedBaseUri() {
    HttpParticipantGateway gateway = gateway(Set.of("https://catalog.internal:443/api/"), false);

    assertThatCode(() -> gateway.validateRegistration(participant("https://CATALOG.internal/api")))
        .doesNotThrowAnyException();
    assertValidationFailure(
        () -> gateway.validateRegistration(participant("https://catalog.internal/admin")));
    assertValidationFailure(
        () -> gateway.validateRegistration(participant("https://catalog.internal:8443/api")));
  }

  @Test
  void plainHttpRequiresAnExplicitSecondGate() {
    Participant participant = participant("http://127.0.0.1:9090");

    assertValidationFailure(
        () -> gateway(Set.of("http://127.0.0.1:9090"), false).validateRegistration(participant));
    assertThatCode(
            () -> gateway(Set.of("http://127.0.0.1:9090"), true).validateRegistration(participant))
        .doesNotThrowAnyException();
  }

  @Test
  void supportsAnExactIpv6BaseUriWithoutChangingItsAuthority() {
    assertThatCode(
            () ->
                gateway(Set.of("https://[::1]:9443/callbacks"), false)
                    .validateRegistration(participant("https://[::1]:9443/callbacks/")))
        .doesNotThrowAnyException();
  }

  @Test
  void invalidConfiguredBaseUriFailsDuringGatewayConstruction() {
    assertThatThrownBy(() -> gateway(Set.of("not-a-uri"), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed-base-uris");
  }

  @Nested
  class CallbackProtocol {

    @Test
    void sendsTheExactWireContractAndReusesTheDeterministicActionId() {
      RecordingHttpClient httpClient = new RecordingHttpClient(204);
      HttpParticipantGateway gateway =
          gateway(
              httpClient,
              Set.of("http://callbacks.internal.test:8080/root/"),
              true,
              Duration.ofSeconds(7));
      Participant participant = participant("http://CALLBACKS.internal.test:8080/root");
      ParticipantGateway.CallbackContext context = new ParticipantGateway.CallbackContext(build());

      gateway.quiesce(participant, context);
      gateway.quiesce(participant, context);

      assertThat(httpClient.sentRequests())
          .hasSize(2)
          .allSatisfy(
              sent -> {
                assertThat(sent.request().method()).isEqualTo("POST");
                assertThat(sent.request().uri())
                    .isEqualTo(
                        URI.create(
                            "http://callbacks.internal.test:8080/root/version-control/quiesce"));
                assertThat(sent.request().timeout()).contains(Duration.ofSeconds(7));
                assertThat(sent.request().headers().firstValue("Content-Type"))
                    .contains("application/json");
                assertThat(sent.request().headers().firstValue("Idempotency-Key"))
                    .contains("45313938-04a5-3c48-bfa6-32ac4cb5e015");
                assertThat(sent.request().headers().firstValue("X-Version-Gate-Protocol-Version"))
                    .contains("1");
                assertThat(sent.body())
                    .isEqualTo(
                        """
                        {"protocolVersion":1,"actionId":"45313938-04a5-3c48-bfa6-32ac4cb5e015","participantId":"catalog-database","buildId":"4dd965e8-4eb5-4bb1-bc58-bd95981f57f4","resourceId":"catalog","targetVersion":42,"baseActiveVersion":41,"fencingToken":17,"leaseExpiresAt":"2030-01-02T03:04:05Z"}\
                        """);
              });
    }

    @Test
    void mapsANonSuccessStatusToParticipantFailure() {
      RecordingHttpClient httpClient = new RecordingHttpClient(503);
      HttpParticipantGateway gateway =
          gateway(
              httpClient,
              Set.of("http://callbacks.internal.test:8080"),
              true,
              Duration.ofSeconds(7));

      assertParticipantFailure(
          () ->
              gateway.capture(
                  participant("http://callbacks.internal.test:8080"),
                  new ParticipantGateway.CallbackContext(build())),
          "capture callback returned HTTP 503");
      assertThat(httpClient.sentRequests()).hasSize(1);
    }

    @Test
    void refusesRedirectResponsesAndClientsThatWouldFollowThem() {
      RecordingHttpClient redirectResponseClient = new RecordingHttpClient(307);
      HttpParticipantGateway gateway =
          gateway(
              redirectResponseClient,
              Set.of("http://callbacks.internal.test:8080"),
              true,
              Duration.ofSeconds(7));

      assertParticipantFailure(
          () ->
              gateway.resume(
                  participant("http://callbacks.internal.test:8080"),
                  new ParticipantGateway.CallbackContext(build())),
          "resume callback returned HTTP 307");
      assertThat(redirectResponseClient.sentRequests()).hasSize(1);

      RecordingHttpClient redirectFollowingClient =
          new RecordingHttpClient(204, HttpClient.Redirect.ALWAYS);
      assertThatThrownBy(
              () ->
                  gateway(
                      redirectFollowingClient,
                      Set.of("http://callbacks.internal.test:8080"),
                      true,
                      Duration.ofSeconds(7)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("disable redirects");
    }
  }

  private static HttpParticipantGateway gateway(Set<String> allowedBaseUris, boolean allowHttp) {
    return gateway(HttpClient.newHttpClient(), allowedBaseUris, allowHttp, Duration.ofSeconds(30));
  }

  private static HttpParticipantGateway gateway(
      HttpClient httpClient,
      Set<String> allowedBaseUris,
      boolean allowHttp,
      Duration requestTimeout) {
    VersionGateProperties properties =
        new VersionGateProperties(
            Duration.ofHours(1),
            DataSize.ofGigabytes(1),
            16,
            Duration.ofSeconds(30),
            new VersionGateProperties.ParticipantProperties(
                Duration.ofSeconds(5), requestTimeout, allowedBaseUris, allowHttp));
    return new HttpParticipantGateway(
        httpClient, JsonMapper.builder().findAndAddModules().build(), properties);
  }

  private static Participant participant(String baseUri) {
    return new Participant("catalog-database", URI.create(baseUri));
  }

  private static void assertValidationFailure(Runnable invocation) {
    assertThatThrownBy(invocation::run)
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  private static void assertParticipantFailure(Runnable invocation, String message) {
    assertThatThrownBy(invocation::run)
        .isInstanceOfSatisfying(
            VersionGateException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.PARTICIPANT_FAILURE);
              assertThat(exception.getMessage()).contains(message);
            });
  }

  private static Build build() {
    return new Build(
        UUID.fromString("4dd965e8-4eb5-4bb1-bc58-bd95981f57f4"),
        "catalog",
        42,
        41L,
        BuildStatus.QUIESCING,
        "release-pipeline",
        17,
        Instant.parse("2030-01-02T03:04:05Z"),
        Instant.parse("2030-01-02T02:04:05Z"),
        Instant.parse("2030-01-02T02:05:05Z"));
  }

  private record SentRequest(HttpRequest request, String body) {}

  private static final class RecordingHttpClient extends HttpClient {

    private final int statusCode;
    private final HttpClient delegate;
    private final List<SentRequest> sentRequests = new ArrayList<>();

    private RecordingHttpClient(int statusCode) {
      this(statusCode, Redirect.NEVER);
    }

    private RecordingHttpClient(int statusCode, Redirect redirect) {
      this.statusCode = statusCode;
      this.delegate = HttpClient.newBuilder().followRedirects(redirect).build();
    }

    private List<SentRequest> sentRequests() {
      return List.copyOf(sentRequests);
    }

    @Override
    public Optional<java.net.CookieHandler> cookieHandler() {
      return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
      return delegate.followRedirects();
    }

    @Override
    public Optional<java.net.ProxySelector> proxy() {
      return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
      return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
      return delegate.sslParameters();
    }

    @Override
    public Optional<java.net.Authenticator> authenticator() {
      return delegate.authenticator();
    }

    @Override
    public Version version() {
      return delegate.version();
    }

    @Override
    public Optional<java.util.concurrent.Executor> executor() {
      return delegate.executor();
    }

    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      sentRequests.add(new SentRequest(request, readBody(request)));
      return new StubHttpResponse<>(request, statusCode);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException("asynchronous requests are not used");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException("asynchronous requests are not used");
    }

    private static String readBody(HttpRequest request) {
      HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
      CompletableFuture<byte[]> result = new CompletableFuture<>();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      publisher.subscribe(
          new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
              subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
              byte[] chunk = new byte[item.remaining()];
              item.get(chunk);
              bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
              result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
              result.complete(bytes.toByteArray());
            }
          });
      return new String(result.join(), StandardCharsets.UTF_8);
    }
  }

  private static final class StubHttpResponse<T> implements HttpResponse<T> {

    private final HttpRequest request;
    private final int statusCode;

    private StubHttpResponse(HttpRequest request, int statusCode) {
      this.request = request;
      this.statusCode = statusCode;
    }

    @Override
    public int statusCode() {
      return statusCode;
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
    }

    @Override
    public T body() {
      return null;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
