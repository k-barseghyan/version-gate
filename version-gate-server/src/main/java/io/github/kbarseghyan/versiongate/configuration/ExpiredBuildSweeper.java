package io.github.kbarseghyan.versiongate.configuration;

import io.github.kbarseghyan.versiongate.application.VersionGateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

final class ExpiredBuildSweeper {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExpiredBuildSweeper.class);

  private final VersionGateService service;

  ExpiredBuildSweeper(VersionGateService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${version-gate.expired-build-sweep-interval:PT30S}")
  void abandonExpiredBuilds() {
    int abandoned = service.abandonExpiredBuilds();
    if (abandoned > 0) {
      LOGGER.info("Abandoned {} expired build(s)", abandoned);
    }
  }
}
