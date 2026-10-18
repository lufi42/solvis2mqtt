package de.sgollmer.solvismax.connection.mqtt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.model.Instances;
import de.sgollmer.solvismax.model.OfflineInstances;
import de.sgollmer.solvismax.model.Solvis;
import de.sgollmer.solvismax.model.objects.ChannelDescription;

class StaleTopicCleanupTest {

	@Test
	void recognisesChannelTopicsOnly(@TempDir final File dir) throws Exception {
		Instances instances = OfflineInstances.create(dir);
		Mqtt mqtt = instances.getMqtt();
		StaleTopicCleanup cleanup = new StaleTopicCleanup(mqtt);
		String base = mqtt.getTopicPrefix() + "/" + mqtt.getSmartHomeId() + "/";

		assertTrue(cleanup.isChannelTopic(base + "mySolvis/S01:Speicher_oben/data"));
		assertTrue(cleanup.isChannelTopic(base + "mySolvis/S01:Speicher_oben/meta"));
		assertFalse(cleanup.isChannelTopic(base + "mySolvis/S01:Speicher_oben/cmnd"));
		assertFalse(cleanup.isChannelTopic(base + "mySolvis/status"));
		assertFalse(cleanup.isChannelTopic(base + "server/meta"));
		assertFalse(cleanup.isChannelTopic("other/" + mqtt.getSmartHomeId() + "/mySolvis/S01:Speicher_oben/data"));
	}

	@Test
	void staleOnlyForUnknownChannelsOfOwnUnits(@TempDir final File dir) throws Exception {
		Instances instances = OfflineInstances.create(dir);
		Mqtt mqtt = instances.getMqtt();
		Solvis solvis = OfflineInstances.firstUnit(instances);
		StaleTopicCleanup cleanup = new StaleTopicCleanup(mqtt);
		String unit = mqtt.getTopicPrefix() + "/" + mqtt.getSmartHomeId() + "/" + solvis.getUnit().getId() + "/";

		ChannelDescription s01 = solvis.getChannelDescription("S01");
		assertNotNull(s01);
		// topic segment = full channel name (id.name) with ':' instead of '.'
		String segment = solvis.getAllSolvisData().get(s01).getName().replace('.', ':');

		assertFalse(cleanup.isStale(unit + segment + "/data", instances), "existing channel");
		assertFalse(cleanup.isStale(unit + segment + "/meta", instances), "existing channel");
		assertTrue(cleanup.isStale(unit + "I-3:Stoerung_Lino/data", instances), "renamed channel");
		assertTrue(cleanup.isStale(unit + "I-3:Stoerung_Lino/meta", instances), "renamed channel");
		assertFalse(cleanup.isStale(unit + "server/meta", instances), "reserved segment");
		assertFalse(cleanup.isStale(unit + "screen/meta", instances), "reserved segment");
		assertFalse(cleanup.isStale(unit + "debugChannel/meta", instances), "reserved segment");
		assertFalse(cleanup.isStale(mqtt.getTopicPrefix() + "/" + mqtt.getSmartHomeId()
				+ "/otherUnit/I-3:Stoerung_Lino/data", instances), "unit of another server");
	}
}
