package lk.routerank.fraud;

import org.springframework.http.HttpStatus;

/**
 * A Turnstile check that didn't pass. The site shows "try again" for both kinds; the code tells them apart.
 */
public abstract sealed class BotCheckException extends RuntimeException {

	private BotCheckException(String message) {
		super(message);
	}

	abstract HttpStatus status();

	abstract String code();

	/** The token was missing, spent, expired or not from this site. */
	public static final class Failed extends BotCheckException {

		public Failed() {
			super("bot check failed");
		}

		@Override
		HttpStatus status() {
			return HttpStatus.FORBIDDEN;
		}

		@Override
		String code() {
			return "bot_check_failed";
		}

	}

	/** Cloudflare couldn't be reached: the action is refused rather than let through unchecked. */
	public static final class Unavailable extends BotCheckException {

		public Unavailable(String message) {
			super(message);
		}

		@Override
		HttpStatus status() {
			return HttpStatus.SERVICE_UNAVAILABLE;
		}

		@Override
		String code() {
			return "bot_check_unavailable";
		}

	}

}
