package de.sgollmer.solvismax.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.connection.mqtt.MqttData;
import de.sgollmer.solvismax.imagepatternrecognition.image.MyImage;
import de.sgollmer.solvismax.model.OfflineInstances;
import de.sgollmer.solvismax.model.Solvis;
import de.sgollmer.solvismax.model.objects.ErrorState;

/** Issue #15: fault details and the error screen are published via MQTT. */
class FaultPublisherTest {

	@Test
	void faultIsPublishedWithImageAndCleared(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir));
		FaultPublisher publisher = solvis.getFaultPublisher();
		List<ISendData> published = new ArrayList<>();
		publisher.publisher = published::add;

		assertFalse(publisher.isActive());
		String idle = publisher.toJson();
		assertTrue(idle.startsWith("{\"state\":\"off\""), idle);
		assertTrue(idle.contains("\"channels\":[]"), idle);

		MyImage image = new MyImage(new BufferedImage(240, 128, BufferedImage.TYPE_INT_RGB));
		publisher.update(new ErrorState.Info(solvis, image, "The Solvis system reports: Error: Message box occured.",
				false), this);

		assertTrue(publisher.isActive());
		assertEquals(2, published.size(), "image and state");
		byte[] png = ((FaultPublisher.ImagePackage) published.get(0)).getPng();
		assertTrue(png.length > 8 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "PNG expected");
		MqttData imageData = published.get(0).createMqttData().iterator().next();
		assertEquals(solvis.getMqtt().getTopicPrefix() + "/" + solvis.getUnit().getId() + "/fault/image",
				solvis.getMqtt().getTopic(imageData));

		String json = ((UnitStatePackage) published.get(1)).getPayload();
		assertTrue(json.startsWith("{\"state\":\"on\""), json);
		assertTrue(json.contains("\"message\":\"The Solvis system reports: Error: Message box occured.\""), json);
		assertTrue(json.contains("\"image\":true"), json);
		assertTrue(json.contains("\"since\":\""), json);
		MqttData stateData = published.get(1).createMqttData().iterator().next();
		assertEquals(solvis.getMqtt().getTopicPrefix() + "/" + solvis.getUnit().getId() + "/fault",
				solvis.getMqtt().getTopic(stateData));

		// visibility change only: nothing new
		publisher.update(null, this);
		assertEquals(2, published.size());

		publisher.update(new ErrorState.Info(solvis, null, "All errors cleared.", true), this);
		assertFalse(publisher.isActive());
		assertEquals(3, published.size());
		String cleared = ((UnitStatePackage) published.get(2)).getPayload();
		assertTrue(cleared.startsWith("{\"state\":\"off\""), cleared);
		assertTrue(cleared.contains("\"image\":false"), cleared);
	}
}
