package io.github.kbarseghyan.versiongate.domain;

import java.net.URI;
import java.util.Objects;

/**
 * Registered callback endpoint participating in coordinated quiescence.
 *
 * @param participantId stable identifier unique within a resource
 * @param baseUri absolute HTTP(S) callback base URI without user info, query, or fragment
 */
public record Participant(String participantId, URI baseUri) {

  /**
   * Creates a validated callback participant.
   *
   * @param participantId stable identifier unique within a resource
   * @param baseUri absolute HTTP(S) callback base URI
   */
  public Participant {
    DomainValidation.requireIdentifier(participantId, "participantId");
    Objects.requireNonNull(baseUri, "baseUri is required");
    if (!baseUri.isAbsolute()
        || (!"http".equalsIgnoreCase(baseUri.getScheme())
            && !"https".equalsIgnoreCase(baseUri.getScheme()))) {
      throw new IllegalArgumentException("baseUri must be an absolute HTTP(S) URI");
    }
    if (baseUri.getHost() == null
        || baseUri.getUserInfo() != null
        || baseUri.getQuery() != null
        || baseUri.getFragment() != null) {
      throw new IllegalArgumentException(
          "baseUri must contain a host and no user info, query, or fragment");
    }
  }
}
