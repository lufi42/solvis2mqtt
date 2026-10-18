package de.sgollmer.solvismax.connection.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.connection.transfer.Command;
import de.sgollmer.solvismax.error.MqttInterfaceException;
import de.sgollmer.solvismax.model.Instances;
import de.sgollmer.solvismax.model.OfflineInstances;

/**
 * The topic structure is part of the external interface:
 * &lt;topicPrefix&gt;/&lt;smartHomeId&gt;/[&lt;unitId&gt;/[&lt;channel&gt;/]]&lt;suffix&gt;.
 */
class MqttTopicTest {

	@Test
	void subscribedTopicsAreAnalysedCorrectly(@TempDir final File dir) throws Exception {
		Instances instances = OfflineInstances.create(dir);
		Mqtt mqtt = instances.getMqtt();
		String base = mqtt.getTopicPrefix() + "/" + mqtt.getSmartHomeId();

		SubscribeData set = mqtt.analyseReceivedTopic(base + "/mySolvis/C06:Anlagenmodus_HK1/cmnd");
		assertEquals(TopicType.UNIT_CHANNEL_COMMAND, set.type);
		assertEquals("mySolvis", set.getUnitId());
		assertEquals("C06.Anlagenmodus_HK1", set.getChannelId());
		assertEquals(Command.SET, set.getCommand());

		SubscribeData update = mqtt.analyseReceivedTopic(base + "/mySolvis/C07:Tagestemperatur_HK1/update");
		assertEquals(TopicType.UNIT_CHANNEL_UPDATE, update.type);
		assertEquals(Command.GET, update.getCommand());

		SubscribeData unitServer = mqtt.analyseReceivedTopic(base + "/mySolvis/server/cmnd");
		assertEquals(TopicType.UNIT_SERVER_COMMAND, unitServer.type);
		assertEquals("mySolvis", unitServer.getUnitId());
		assertEquals(Command.SERVER_COMMAND, unitServer.getCommand());

		SubscribeData server = mqtt.analyseReceivedTopic(base + "/server/cmnd");
		assertEquals(TopicType.SERVER_COMMAND, server.type);
		assertNull(server.getUnitId());

		SubscribeData online = mqtt.analyseReceivedTopic(base + "/online");
		assertEquals(TopicType.CLIENT_ONLINE, online.type);
		assertEquals(Command.CLIENT_ONLINE, online.getCommand());
	}

	@Test
	void foreignPrefixIsRejected(@TempDir final File dir) throws Exception {
		Instances instances = OfflineInstances.create(dir);
		Mqtt mqtt = instances.getMqtt();
		assertThrows(MqttInterfaceException.class,
				() -> mqtt.analyseReceivedTopic("other/" + mqtt.getSmartHomeId() + "/mySolvis/server/cmnd"));
		assertThrows(MqttInterfaceException.class,
				() -> mqtt.analyseReceivedTopic(mqtt.getTopicPrefix() + "/" + mqtt.getSmartHomeId() + "/unknown"));
	}
}
