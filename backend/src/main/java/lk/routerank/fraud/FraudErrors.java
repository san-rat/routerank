package lk.routerank.fraud;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns a failed bot check into a status and a code the site can read, from whichever controller ran it. */
@RestControllerAdvice
class FraudErrors {

	@ExceptionHandler(BotCheckException.class)
	ResponseEntity<Map<String, String>> botCheck(BotCheckException e) {
		return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
	}

}
