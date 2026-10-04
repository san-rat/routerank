package lk.routerank.scoring;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cloudflare R2 through its S3 API (path-style URLs, region "auto"), signed with SigV4. Only puts and deletes:
 * the job keeps its own record of what it uploaded, so it never lists the bucket (a list is a paid write).
 */
class R2Store implements ObjectStore {

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final URI endpoint;

	private final String bucket;

	private final SigV4 signer;

	private final Clock clock;

	/**
	 * @param endpoint {@code https://<ACCOUNT_ID>.r2.cloudflarestorage.com}
	 */
	R2Store(URI endpoint, String bucket, String accessKeyId, String secretAccessKey, Clock clock) {
		this.endpoint = endpoint;
		this.bucket = bucket;
		this.signer = new SigV4(accessKeyId, secretAccessKey, "auto", "s3");
		this.clock = clock;
	}

	@Override
	public void put(String key, byte[] body, String contentType, String cacheControl) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", contentType);
		headers.put("Cache-Control", cacheControl);
		send("PUT", key, body, headers);
	}

	@Override
	public void delete(String key) {
		send("DELETE", key, new byte[0], new LinkedHashMap<>());
	}

	private void send(String method, String key, byte[] body, Map<String, String> headers) {
		String path = SigV4.encodePath("/" + bucket + "/" + key);
		URI uri = endpoint.resolve(path);
		String hash = body.length == 0 ? SigV4.EMPTY_SHA256 : SigV4.sha256Hex(body);
		Instant now = clock.instant();
		headers.put("host", uri.getRawAuthority());
		headers.put("x-amz-content-sha256", hash);
		headers.put("x-amz-date", SigV4.AMZ_DATE.format(now));
		String authorization = signer.authorization(method, path, headers, hash, now);

		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(30))
			.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofByteArray(body))
			.header("Authorization", authorization);
		headers.forEach((name, value) -> {
			if (!name.equals("host")) { // HttpClient sets Host itself, from the URI
				request.header(name, value);
			}
		});
		try {
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new PublishException(method + " " + key + " failed: HTTP " + response.statusCode() + " "
						+ response.body());
			}
		}
		catch (IOException e) {
			throw new PublishException(method + " " + key + " failed: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PublishException(method + " " + key + " interrupted", e);
		}
	}

	static class PublishException extends RuntimeException {

		PublishException(String message) {
			super(message);
		}

		PublishException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
