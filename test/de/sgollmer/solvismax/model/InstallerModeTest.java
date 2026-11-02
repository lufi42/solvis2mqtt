package de.sgollmer.solvismax.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collection;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.connection.mqtt.MqttData;
import de.sgollmer.solvismax.model.objects.ChannelDescription;

/**
 * Issue #25: installer settings are recognised from the control.xml, read
 * last and written only while the installer mode is active.
 */
class InstallerModeTest {

	@Test
	void installerChannelsAreRecognisedFromTheScreenChain(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, true));
		assertTrue(solvis.isAdmin());
		for (String id : new String[] { "C47", "C48", "C49" }) {
			ChannelDescription description = solvis.getChannelDescription(id);
			assertNotNull(description, id + " missing");
			assertTrue(solvis.isInstallerChannel(description), id + " is in the installer menu");
		}
		for (String id : new String[] { "C06", "C07", "C08" }) {
			ChannelDescription description = solvis.getChannelDescription(id);
			assertNotNull(description, id + " missing");
			assertFalse(solvis.isInstallerChannel(description), id + " is a user setting");
		}
	}

	@Test
	void withAdminInstallerChannelsAreReadableButLast(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, true));
		Collection<ChannelDescription> readable = solvis.getSolvisDescription().getChannelDescriptions()
				.getReadableControlChannels(solvis);
		assertTrue(readable.contains(solvis.getChannelDescription("C47")), "C47 readable with Admin");
		assertTrue(readable.contains(solvis.getChannelDescription("C49")), "C49 readable with Admin");

		boolean installerSeen = false;
		for (Iterator<ChannelDescription> it = readable.iterator(); it.hasNext();) {
			boolean installer = solvis.isInstallerChannel(it.next());
			assertFalse(installerSeen && !installer, "user setting after an installer setting in the read order");
			installerSeen |= installer;
		}
		assertTrue(installerSeen);
	}

	@Test
	void writesToInstallerChannelsNeedTheInstallerMode(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, true));
		ChannelDescription c47 = solvis.getChannelDescription("C47");
		ChannelDescription c06 = solvis.getChannelDescription("C06");
		InstallerMode mode = solvis.getInstallerMode();

		assertFalse(mode.isActive());
		assertTrue(solvis.isInstallerWriteAllowed(c06), "user settings are always writable");
		assertFalse(solvis.isInstallerWriteAllowed(c47), "installer settings need the installer mode");
		assertTrue(mode.toJson().startsWith("{\"state\":\"off\""), mode.toJson());

		mode.enable(300L);
		assertTrue(mode.isActive());
		assertTrue(mode.getRemaining_ms() > 0);
		assertTrue(solvis.isInstallerWriteAllowed(c47));
		String json = mode.toJson();
		assertTrue(json.startsWith("{\"state\":\"on\""), json);
		assertTrue(json.contains("\"until\":\""), json);

		MqttData data = mode.getSendData().createMqttData().iterator().next();
		String topic = solvis.getMqtt().getTopic(data);
		assertEquals(solvis.getMqtt().getTopicPrefix() + "/" + solvis.getUnit().getId() + "/installer_mode", topic);

		Thread.sleep(700L);
		assertFalse(mode.isActive(), "installer mode must expire on its own");
		assertFalse(solvis.isInstallerWriteAllowed(c47));

		mode.enable(60000L);
		mode.disable();
		assertFalse(mode.isActive());
		assertEquals(15 * 60000L, solvis.getUnit().getInstallerModeTime_ms(), "default from the example base.xml");
	}
}
