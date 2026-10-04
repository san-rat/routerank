package lk.routerank.routes;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lk.routerank.auth.SignedInUser;
import lk.routerank.fraud.Devices;
import lk.routerank.fraud.Holds;
import lk.routerank.fraud.SaveLimits;
import lk.routerank.fraud.Turnstile;
import lk.routerank.routes.Views.MyRoutes;
import lk.routerank.routes.Views.Preview;
import lk.routerank.routes.Views.Refused;
import lk.routerank.routes.Views.RouteView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** My routes and the add-route steps. Everything here needs sign-in. */
@RestController
@RequestMapping("/api/routes")
class RoutesController {

	private final RouteService routes;

	/** Previews run on drag end (debounced in the browser), so a minute allows plenty of adjusting. */
	private final RateLimiter previews;

	private final RateLimiter changes;

	private final Turnstile turnstile;

	private final Devices devices;

	private final SaveLimits saveLimits;

	private final Holds holds;

	RoutesController(RouteService routes, Clock clock, Turnstile turnstile, Devices devices, SaveLimits saveLimits,
			Holds holds) {
		this.routes = routes;
		this.turnstile = turnstile;
		this.devices = devices;
		this.saveLimits = saveLimits;
		this.holds = holds;
		this.previews = new RateLimiter(60, Duration.ofMinutes(1), clock);
		this.changes = new RateLimiter(30, Duration.ofMinutes(10), clock);
	}

	@GetMapping
	MyRoutes list(@AuthenticationPrincipal SignedInUser user) {
		return routes.list(user.id());
	}

	/** Works a route out from its points: snapped points, both directions, length, name and any problems. */
	@PostMapping("/preview")
	Preview preview(@AuthenticationPrincipal SignedInUser user, @Valid @RequestBody RouteInput input) {
		previews.check(user.id());
		return routes.preview(user.id(), input);
	}

	/** Saves a new route in a slot, pushing the routes in the way down. 422 lists the rules it breaks. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	RouteView create(@AuthenticationPrincipal SignedInUser user, @Valid @RequestBody RouteInput input,
			HttpServletRequest request) {
		changes.check(user.id());
		if (honeypot(user, input)) {
			return routes.unsaved(input);
		}
		checkSave(user, input, request);
		return routes.create(user.id(), input);
	}

	/** Replaces a route's points (and moves it, if {@code slot} differs). */
	@PutMapping("/{id}")
	RouteView update(@AuthenticationPrincipal SignedInUser user, @PathVariable long id,
			@Valid @RequestBody RouteInput input, HttpServletRequest request) {
		changes.check(user.id());
		if (honeypot(user, input)) {
			return routes.unsaved(input);
		}
		checkSave(user, input, request);
		return routes.update(user.id(), id, input);
	}

	/** Only a bot fills in the hidden field: its account is flagged and held, and nothing is saved. */
	private boolean honeypot(SignedInUser user, RouteInput input) {
		if (input.website() == null || input.website().isBlank()) {
			return false;
		}
		holds.honeypot(user.id());
		return true;
	}

	/** Turnstile, then the device signal and the per-device and per-IP limits (new and edited routes only). */
	private void checkSave(SignedInUser user, RouteInput input, HttpServletRequest request) {
		String ip = request.getRemoteAddr();
		turnstile.check(input.turnstile(), Turnstile.SAVE, ip);
		byte[] device = devices.record(user.id(), input.device()).orElse(null);
		saveLimits.check(device, ip);
		if (device != null) {
			holds.deviceSeen(device);
		}
	}

	/** Puts every route in a new slot. */
	@PutMapping("/order")
	MyRoutes reorder(@AuthenticationPrincipal SignedInUser user, @Valid @RequestBody Order order) {
		changes.check(user.id());
		Map<Long, Integer> slots = order.routes().stream()
			.collect(Collectors.toMap(SlotAssignment::routeId, SlotAssignment::slot, (a, b) -> {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "each route can be listed once");
			}));
		return routes.reorder(user.id(), slots);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@AuthenticationPrincipal SignedInUser user, @PathVariable long id) {
		changes.check(user.id());
		routes.remove(user.id(), id);
	}

	@ExceptionHandler(RouteRefusedException.class)
	ResponseEntity<Refused> refused(RouteRefusedException e) {
		return ResponseEntity.status(422).body(new Refused(e.problems()));
	}

	record Order(@NotNull @Size(max = Slots.COUNT) List<@NotNull @Valid SlotAssignment> routes) {
	}

	record SlotAssignment(@NotNull Long routeId, @NotNull @Min(1) @Max(3) Integer slot) {
	}

}
