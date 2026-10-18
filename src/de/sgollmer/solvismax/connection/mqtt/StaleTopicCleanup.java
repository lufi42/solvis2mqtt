/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.connection.mqtt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.paho.client.mqttv3.MqttException;

import de.sgollmer.solvismax.Constants;
import de.sgollmer.solvismax.log.LogManager;
import de.sgollmer.solvismax.log.LogManager.ILogger;
import de.sgollmer.solvismax.model.Instances;
import de.sgollmer.solvismax.model.Solvis;

/**
 * Removes retained <code>data</code> and <code>meta</code> messages of
 * channels which don't exist any more, e.g. after a configuration change
 * renamed a channel (<code>I-3:Stoerung_Lino</code> to
 * <code>I-3:Stoerung_Pelletkessel</code>).
 *
 * Once after the first connection the server subscribes to
 * <code>&lt;prefix&gt;/&lt;smartHomeId&gt;/+/+/data</code> and
 * <code>.../meta</code>, collects the retained messages the broker delivers,
 * deletes those whose unit is served by this instance but whose channel is
 * unknown, and unsubscribes again.
 */
public class StaleTopicCleanup {

	private static final ILogger logger = LogManager.getInstance().getLogger(StaleTopicCleanup.class);

	/** time to wait for the retained messages after the subscription */
	static final int COLLECT_TIME_MS = 10000;

	private static final String[] RESERVED_CHANNEL_SEGMENTS = new String[] { Constants.Mqtt.SERVER,
			Constants.Mqtt.SCREEN_PREFIX, Constants.Mqtt.DEBUG_CHANNEL_PREFIX };

	private final Mqtt mqtt;
	private final Set<String> retainedOnBroker = new HashSet<>();
	private boolean collecting = false;
	private boolean done = false;

	StaleTopicCleanup(final Mqtt mqtt) {
		this.mqtt = mqtt;
	}

	private String base() {
		return this.mqtt.getTopicPrefix() + '/' + this.mqtt.getSmartHomeId() + '/';
	}

	String[] getSubscribeFilters() {
		String base = this.base();
		return new String[] { base + "+/+/" + Constants.Mqtt.DATA_SUFFIX, base + "+/+/" + Constants.Mqtt.META_SUFFIX };
	}

	/**
	 * @return true if the topic is a channel data or meta topic of this server
	 *         (&lt;prefix&gt;/&lt;smartHomeId&gt;/&lt;unit&gt;/&lt;channel&gt;/data|meta)
	 */
	boolean isChannelTopic(final String topic) {
		if (!topic.startsWith(this.base())) {
			return false;
		}
		String[] parts = topic.substring(this.base().length()).split("/");
		return parts.length == 3
				&& (Constants.Mqtt.DATA_SUFFIX.equals(parts[2]) || Constants.Mqtt.META_SUFFIX.equals(parts[2]));
	}

	void received(final String topic, final byte[] payload) {
		synchronized (this) {
			if (!this.collecting || payload == null || payload.length == 0) {
				return;
			}
			this.retainedOnBroker.add(topic);
		}
	}

	/**
	 * Starts the cleanup in an own thread (subscribing from the MQTT callback
	 * thread would block). Runs only once per process.
	 */
	synchronized void start(final Instances instances) {
		if (this.done || this.collecting) {
			return;
		}
		this.collecting = true;
		Thread thread = new Thread(() -> this.run(instances), "MqttStaleTopicCleanup");
		thread.setDaemon(true);
		thread.start();
	}

	private void run(final Instances instances) {
		String[] filters = this.getSubscribeFilters();
		try {
			this.mqtt.subscribe(filters);
		} catch (MqttException e) {
			logger.warn("Stale topic cleanup: subscription failed (" + e.getMessage() + "), cleanup skipped.");
			synchronized (this) {
				this.collecting = false;
			}
			return;
		}
		try {
			Thread.sleep(COLLECT_TIME_MS);
		} catch (InterruptedException e) {
		}
		Collection<String> topics;
		synchronized (this) {
			this.collecting = false;
			this.done = true;
			topics = new ArrayList<>(this.retainedOnBroker);
			this.retainedOnBroker.clear();
		}
		try {
			this.mqtt.unsubscribe(filters);
		} catch (MqttException e) {
			logger.warn("Stale topic cleanup: unsubscribe failed (" + e.getMessage() + ").");
		}
		int deleted = 0;
		for (String topic : topics) {
			if (this.isStale(topic, instances)) {
				this.mqtt.publish(new MqttData(topic, "", 0, true));
				logger.info("Stale topic <" + topic + "> deleted.");
				++deleted;
			}
		}
		logger.info("Stale topic cleanup: " + topics.size() + " retained channel topics checked, " + deleted
				+ " deleted.");
	}

	/**
	 * A topic is stale if its unit is served by this instance (topics of other
	 * units are left alone) and the channel is neither known nor one of the
	 * reserved segments (server, screen, debugChannel).
	 */
	boolean isStale(final String topic, final Instances instances) {
		String[] parts = topic.substring(this.base().length()).split("/");
		if (parts.length != 3) {
			return false;
		}
		Solvis solvis = instances.getUnit(parts[0]);
		if (solvis == null) {
			return false;
		}
		for (String reserved : RESERVED_CHANNEL_SEGMENTS) {
			if (reserved.equals(parts[1])) {
				return false;
			}
		}
		String name = TopicType.formatChannelSubscribe(parts[1]);
		return solvis.getAllSolvisData().getByName(name) == null;
	}
}
