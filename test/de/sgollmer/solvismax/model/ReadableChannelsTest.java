package de.sgollmer.solvismax.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.data.SolvisData;

/**
 * Issue #21: channels on screens the server cannot reach (installer menu
 * without the feature Admin) must not be offered to the smart home, and
 * channels with a fix value must carry that value from the start.
 */
class ReadableChannelsTest {

	@Test
	void installerMenuChannelsAreNotReadableWithoutAdmin(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir));
		assertFalse(solvis.isAdmin(), "the example configuration has Admin=false");
		Collection<ChannelDescription> readable = solvis.getSolvisDescription().getChannelDescriptions()
				.getReadableControlChannels(solvis);
		assertFalse(readable.isEmpty());

		assertTrue(readable.contains(solvis.getChannelDescription("C06")), "C06 is on the home screen");
		assertTrue(readable.contains(solvis.getChannelDescription("C07")), "C07 is reachable without Admin");
		for (String id : new String[] { "C47", "C48", "C49" }) {
			ChannelDescription description = solvis.getChannelDescription(id);
			assertNotNull(description, id + " missing in the example configuration");
			assertFalse(readable.contains(description), id + " is in the installer menu and needs Admin");
		}
	}

	@Test
	void fixValueIsVisibleToTheSmartHomeSide(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir));
		SolvisData c47 = solvis.getAllSolvisData().get("C47");
		assertNotNull(c47);
		assertTrue(c47.isFix(), "the example base.xml fixes C47 to 12");
		assertNotNull(c47.getSmartHomeData(), "C47 is a smart home channel");
		assertNotNull(c47.getSmartHomeData().getData(), "fix value not propagated to the smart home data");
		assertEquals(12, c47.getSmartHomeData().getData().getInt().intValue());
		assertNotNull(c47.getSmartHomeData().getMqttData(), "fix value must be publishable");
	}
}
