package de.sgollmer.solvismax.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.data.IMode;
import de.sgollmer.solvismax.model.objects.data.SingleData;
import de.sgollmer.solvismax.model.objects.data.StringData;

/**
 * The textual form of a value (MQTT payload, pending restore file) must
 * survive interpret -> normalize -> toString -> interpret unchanged. The MQTT
 * command path and the restore of dependencies rely on it.
 */
class ChannelValueRoundTripTest {

	private static SingleData<?> roundTrip(final ChannelDescription description, final String text) throws Exception {
		SingleData<?> interpreted = description.interpretSetData(new StringData(text, 1L), false);
		assertNotNull(interpreted, description.getId() + ": <" + text + "> not interpretable");
		SingleData<?> normalized = description.normalize(interpreted);
		assertNotNull(normalized, description.getId() + ": normalize returned null");
		String back = normalized.toString();
		SingleData<?> again = description.interpretSetData(new StringData(back, 2L), false);
		assertEquals(interpreted, again, description.getId() + ": <" + text + "> -> <" + back + "> changed the value");
		return normalized;
	}

	@Test
	void modeChannelKeepsEveryModeName(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir));
		ChannelDescription c06 = solvis.getChannelDescription("C06");
		assertNotNull(c06);
		assertTrue(c06.isWriteable());
		assertFalse(c06.getModes().isEmpty());
		for (IMode<?> mode : c06.getModes()) {
			SingleData<?> normalized = roundTrip(c06, mode.getName());
			assertEquals(mode.getName(), normalized.toString());
		}
	}

	@Test
	void temperatureChannelKeepsHalfDegrees(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir));
		ChannelDescription c07 = solvis.getChannelDescription("C07");
		assertNotNull(c07, "C07 (Tagestemperatur_HK1) missing in the example configuration");
		assertTrue(c07.isWriteable());
		roundTrip(c07, "21.5");
		roundTrip(c07, "18");
	}

	@Test
	void exampleConfigurationHasStatusAndHeatingCircuitChannels(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir));
		for (String id : new String[] { "S01", "S10", "C06", "C07", "C08" }) {
			ChannelDescription description = solvis.getChannelDescription(id);
			assertNotNull(description, id + " missing");
			assertEquals(id, description.getId());
			String name = solvis.getAllSolvisData().get(description).getName();
			assertTrue(name.startsWith(id + "."), name);
		}
	}
}
