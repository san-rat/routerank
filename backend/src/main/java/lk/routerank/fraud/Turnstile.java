package lk.routerank.fraud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cloudflare Turnstile on sign-in and route saves (new and edited), never on previews, reorders or removals.
 * Each token is checked once with Cloudflare and must carry the action it was made for, so a sign-in token can't
 * save a route. Without a secret (local development) the check is off.
 */
@Service
public class Turnstile {

	public static final String SIGN_IN = "signin";

	public static final String SAVE = "save";

	private static final Logger log = LoggerFactory.getLogger(Turnstile.class);

	private final TurnstileProperties props;

	private final TurnstileVerifier verifier;

	Turnstile(TurnstileProperties props, TurnstileVerifier verifier) {
		this.props = props;
		this.verifier = verifier;
		if (!props.enabled()) {
			log.warn("routerank.turnstile.secret is not set: sign-in and saves are not bot-checked");
		}
	}

	/**
	 * @throws BotCheckException.Failed when the token is missing or Cloudflare doesn't accept it for this action
	 * @throws BotCheckException.Unavailable when Cloudflare can't be reached
	 */
	public void check(String token, String action, String remoteIp) {
		if (!props.enabled()) {
			return;
		}
		if (token == null || token.isBlank() || token.length() > 2048) {
			throw new BotCheckException.Failed();
		}
		TurnstileVerifier.Result result = verifier.verify(props.secret(), token, remoteIp);
		boolean actionOk = result.action() == null || result.action().isBlank() || result.action().equals(action);
		boolean hostOk = props.hostnames().isEmpty() || props.hostnames().contains(result.hostname());
		if (!result.success() || !actionOk || !hostOk) {
			log.info("Turnstile refused a {} token: {} (action {}, host {})", action, result.errorCodes(),
					result.action(), result.hostname());
			throw new BotCheckException.Failed();
		}
	}

}
