package lk.routerank.routes;

import java.util.List;

/** A save broke one or more rules; answered as 422 with the problems. */
class RouteRefusedException extends RuntimeException {

	private final List<Problem> problems;

	RouteRefusedException(List<Problem> problems) {
		super("Route refused: " + problems.stream().map(p -> p.code().name()).toList());
		this.problems = List.copyOf(problems);
	}

	List<Problem> problems() {
		return problems;
	}

}
