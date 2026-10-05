package lk.routerank.fraud;

import java.util.List;

/**
 * Asks Cloudflare whether a Turnstile token is good. Tests replace it, so they never call Cloudflare.
 */
public interface TurnstileVerifier {

	/**
	 * @throws BotCheckException.Unavailable when Cloudflare can't be reached or answers with an error
	 */
	Result verify(String secret, String token, String remoteIp);

	/**
	 * Cloudflare's answer.
	 *
	 * @param action the action the widget was rendered with
	 * @param hostname the site the token was made on
	 */
	record Result(boolean success, String action, String hostname, List<String> errorCodes) {
	}

}
