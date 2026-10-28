/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.connection.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ValueMapTemplateTest {

	@Test
	void codesAreMappedWithFallback() {
		assertEquals("{{ {'1': 'keine', '2': 'Warmwasser'}.get(value | int(-1) | string, 'Code ' ~ value) }}",
				HomeAssistantDiscovery.valueMapTemplate("1=keine, 2 = Warmwasser"));
	}

	@Test
	void malformedEntriesAndQuotesAreIgnored() {
		assertEquals("{{ {'3': 'Tags'}.get(value | int(-1) | string, 'Code ' ~ value) }}",
				HomeAssistantDiscovery.valueMapTemplate("x,3=Tag's,=leer"));
	}
}
