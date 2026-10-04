package lk.routerank.scoring;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The scoring job and where it publishes (see the Architecture doc, "The 30-minute scoring job").
 *
 * @param schedule cron (UTC) for the job; "-" turns the schedule off (tests); admins can still run it
 * @param publish R2 settings; without them the job still scores (My routes shows busiest stretches) but
 * publishes nothing
 */
@ConfigurationProperties("routerank.scoring")
record ScoringProperties(@DefaultValue("0 0,30 * * * *") String schedule, @DefaultValue Publish publish) {

	/**
	 * @param endpoint R2's S3 endpoint, {@code https://<ACCOUNT_ID>.r2.cloudflarestorage.com}
	 * @param bucket the data bucket ({@code routerank-data}); keep it apart from tiles and graphs
	 * @param accessKeyId an R2 API token's Access Key ID, scoped to that bucket (environment only)
	 * @param secretAccessKey that token's Secret Access Key (environment only)
	 * @param monthlyWriteBudget uploads stop once a month (UTC) reaches this many writes; R2's free tier is 1 million
	 * @param retention files no manifest has referenced for this long are deleted
	 * @param directory local development only: write the files to this folder instead of R2
	 */
	record Publish(URI endpoint, String bucket, String accessKeyId, String secretAccessKey,
			@DefaultValue("800000") int monthlyWriteBudget, @DefaultValue("2d") Duration retention, String directory) {

		boolean enabled() {
			return endpoint != null && notBlank(bucket) && notBlank(accessKeyId) && notBlank(secretAccessKey);
		}

		private static boolean notBlank(String s) {
			return s != null && !s.isBlank();
		}

	}

}
