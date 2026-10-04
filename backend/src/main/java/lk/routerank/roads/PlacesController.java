package lk.routerank.roads;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlacesController {

	static final int LIMIT = 8;

	private final Roads roads;

	PlacesController(Roads roads) {
		this.roads = roads;
	}

	/** Search box on the add-route steps: places whose name starts with {@code q}. */
	@GetMapping("/api/places")
	List<Place> search(@RequestParam @NotBlank @Size(max = 60) String q) {
		return roads.searchPlaces(q, LIMIT);
	}

}
