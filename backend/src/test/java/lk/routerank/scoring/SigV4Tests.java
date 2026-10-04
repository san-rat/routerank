package lk.routerank.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Against the AWS SigV4 test suite (as in botocore's tests/unit/auth/aws4_testsuite: get-vanilla and
 * post-x-www-form-urlencoded), whose credentials and expected signatures are published.
 */
class SigV4Tests {

	static final SigV4 SIGNER = new SigV4("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "us-east-1",
			"service");

	static final Instant TIME = Instant.parse("2015-08-30T12:36:00Z");

	@Test
	void getVanilla() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Host", "example.amazonaws.com");
		headers.put("X-Amz-Date", "20150830T123600Z");
		assertThat(SIGNER.authorization("GET", "/", headers, SigV4.EMPTY_SHA256, TIME))
			.isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
					+ "SignedHeaders=host;x-amz-date, "
					+ "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
	}

	@Test
	void postWithABody() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/x-www-form-urlencoded");
		headers.put("Host", "example.amazonaws.com");
		headers.put("X-Amz-Date", "20150830T123600Z");
		String body = SigV4.sha256Hex("Param1=value1".getBytes(StandardCharsets.UTF_8));
		assertThat(body).isEqualTo("9095672bbd1f56dfc5b65f3e153adc8731a4a654192329106275f4c7b24d0b6e");
		assertThat(SIGNER.authorization("POST", "/", headers, body, TIME))
			.isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
					+ "SignedHeaders=content-type;host;x-amz-date, "
					+ "Signature=ff11897932ad3f4e8b18135d722051e5ac45fc38421b1da7b9d196a0fe09473a");
	}

	@Test
	void encodesKeysTheS3Way() {
		assertThat(SigV4.encodePath("/routerank-data/heat/western-3f9a.json"))
			.isEqualTo("/routerank-data/heat/western-3f9a.json");
		assertThat(SigV4.encodePath("/b/a b+c~é")).isEqualTo("/b/a%20b%2Bc~%C3%A9");
	}

}
