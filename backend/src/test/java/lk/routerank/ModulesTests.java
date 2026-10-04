package lk.routerank;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Modules only use each other's public parts (Spring Modulith's checks run on ArchUnit). */
class ModulesTests {

	static final ApplicationModules MODULES = ApplicationModules.of(RouterankApiApplication.class);

	@Test
	void modulesRespectTheirBoundaries() {
		MODULES.verify();
	}

}
