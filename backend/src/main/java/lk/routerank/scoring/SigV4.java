package lk.routerank.scoring;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for single requests with no query string: enough to put and delete objects on
 * R2's S3 API without the AWS SDK. Checked against the AWS SigV4 test suite in the tests.
 */
final class SigV4 {

	static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private final String accessKeyId;

	private final String secretAccessKey;

	private final String region;

	private final String service;

	SigV4(String accessKeyId, String secretAccessKey, String region, String service) {
		this.accessKeyId = accessKeyId;
		this.secretAccessKey = secretAccessKey;
		this.region = region;
		this.service = service;
	}

	/**
	 * The Authorization header value.
	 *
	 * @param path the URI-encoded path ("/bucket/key")
	 * @param headers every header to sign, including host and x-amz-date (names in any case)
	 * @param payloadSha256 hex SHA-256 of the body
	 */
	String authorization(String method, String path, Map<String, String> headers, String payloadSha256, Instant time) {
		Map<String, String> canonical = new TreeMap<>();
		headers.forEach((name, value) -> canonical.put(name.toLowerCase(Locale.ROOT), value.strip()));
		String signedHeaders = String.join(";", canonical.keySet());
		String canonicalRequest = method + "\n" + path + "\n\n"
				+ canonical.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue() + "\n").collect(Collectors.joining())
				+ "\n" + signedHeaders + "\n" + payloadSha256;
		String date = AMZ_DATE.format(time).substring(0, 8);
		String scope = date + "/" + region + "/" + service + "/aws4_request";
		String stringToSign = "AWS4-HMAC-SHA256\n" + AMZ_DATE.format(time) + "\n" + scope + "\n" + sha256Hex(canonicalRequest
			.getBytes(StandardCharsets.UTF_8));
		byte[] key = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
		key = hmac(key, region);
		key = hmac(key, service);
		key = hmac(key, "aws4_request");
		String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
		return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope + ", SignedHeaders=" + signedHeaders
				+ ", Signature=" + signature;
	}

	static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** S3's URI encoding of an object key: every byte except unreserved characters and "/". */
	static String encodePath(String path) {
		StringBuilder out = new StringBuilder();
		for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
			char c = (char) (b & 0xff);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
					|| c == '.' || c == '~' || c == '/') {
				out.append(c);
			}
			else {
				out.append('%').append(String.format("%02X", b & 0xff));
			}
		}
		return out.toString();
	}

}
