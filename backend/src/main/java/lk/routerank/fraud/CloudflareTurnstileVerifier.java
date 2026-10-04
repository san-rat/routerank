package lk.routerank.fraud;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cloudflare's siteverify: a form POST with the secret, the token and the visitor's IP. */
@Component
class CloudflareTurnstileVerifier implements TurnstileVerifier {

	private final TurnstileProperties props;

	private final JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	CloudflareTurnstileVerifier(TurnstileProperties props, JsonMapper json) {
		this.props = props;
		this.json = json;
	}

	@Override
	public Result verify(String secret, String token, String remoteIp) {
		StringBuilder form = new StringBuilder()
			.append("secret=").append(encode(secret))
			.append("&response=").append(encode(token));
		if (remoteIp != null && !remoteIp.isBlank()) {
			form.append("&remoteip=").append(encode(remoteIp));
		}
		HttpRequest request = HttpRequest.newBuilder(props.verifyUrl())
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form.toString()))
			.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new BotCheckException.Unavailable("Turnstile unreachable: " + e.getMessage());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BotCheckException.Unavailable("interrupted");
		}
		if (response.statusCode() != 200) {
			throw new BotCheckException.Unavailable("Turnstile answered " + response.statusCode());
		}
		JsonNode body = json.readTree(response.body());
		List<String> errors = new ArrayList<>();
		body.path("error-codes").forEach(code -> errors.add(code.asString()));
		return new Result(body.path("success").asBoolean(false), text(body, "action"), text(body, "hostname"), errors);
	}

	private static String text(JsonNode body, String field) {
		JsonNode node = body.get(field);
		return node == null || node.isNull() ? null : node.asString();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
