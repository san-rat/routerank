package lk.routerank.roads;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Routing is turned off (no graph configured), so routes can't be worked out or saved. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class RoutingUnavailableException extends RuntimeException {

	public RoutingUnavailableException() {
		super("Routing is not available");
	}

}
