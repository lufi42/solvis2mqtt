/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.connection;

import java.util.Collection;
import java.util.Collections;

import de.sgollmer.solvismax.connection.mqtt.MqttData;
import de.sgollmer.solvismax.connection.mqtt.TopicType;
import de.sgollmer.solvismax.connection.transfer.JsonPackage;
import de.sgollmer.solvismax.model.Solvis;

/**
 * A simple retained state of a unit published on a unit level topic
 * (&lt;topicPrefix&gt;/&lt;unitId&gt;/&lt;suffix&gt;), e.g. the installer mode or
 * whether GUI writes are enabled.
 */
public class UnitStatePackage implements ISendData {

	private final TopicType topicType;
	private final Solvis solvis;
	private final String payload;

	public UnitStatePackage(final TopicType topicType, final Solvis solvis, final String payload) {
		this.topicType = topicType;
		this.solvis = solvis;
		this.payload = payload;
	}

	public String getPayload() {
		return this.payload;
	}

	@Override
	public JsonPackage createJsonPackage() {
		return null;
	}

	@Override
	public Collection<MqttData> createMqttData() {
		return Collections.singletonList(new MqttData(this.topicType, this.solvis, null, this.payload, 0, true));
	}
}
