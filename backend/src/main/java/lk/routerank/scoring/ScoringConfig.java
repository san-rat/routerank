package lk.routerank.scoring;

import java.nio.file.Path;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ScoringConfig {

	private static final Logger log = LoggerFactory.getLogger(ScoringConfig.class);

	@Bean
	ObjectStore objectStore(ScoringProperties props, Clock clock) {
		ScoringProperties.Publish p = props.publish();
		if (!p.enabled() && p.directory() != null && !p.directory().isBlank()) {
			log.info("Publishing rankings to the folder {}", Path.of(p.directory()).toAbsolutePath());
			return new FolderStore(Path.of(p.directory()));
		}
		if (!p.enabled()) {
			log.warn("routerank.scoring.publish is not set: rankings are scored but not published to R2");
			return ObjectStore.NONE;
		}
		return new R2Store(p.endpoint(), p.bucket(), p.accessKeyId(), p.secretAccessKey(), clock);
	}

}
