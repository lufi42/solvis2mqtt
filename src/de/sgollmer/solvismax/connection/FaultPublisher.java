/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.connection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;

import de.sgollmer.solvismax.connection.mqtt.MqttData;
import de.sgollmer.solvismax.connection.mqtt.TopicType;
import de.sgollmer.solvismax.connection.transfer.JsonPackage;
import de.sgollmer.solvismax.imagepatternrecognition.image.MyImage;
import de.sgollmer.solvismax.log.LogManager;
import de.sgollmer.solvismax.log.LogManager.ILogger;
import de.sgollmer.solvismax.model.Solvis;
import de.sgollmer.solvismax.model.objects.ErrorState;
import de.sgollmer.solvismax.model.objects.Observer.IObserver;

/**
 * Publishes the details of a plant fault (issue #15), the replacement of the
 * former exception mail:
 * <ul>
 * <li>&lt;topicPrefix&gt;/&lt;unitId&gt;/fault (retained JSON):
 * <code>{"state":"on","since":"...","message":"...","channels":["I-3.Stoerung_Pelletkessel"],"message_screen":true,"image":true}</code></li>
 * <li>&lt;topicPrefix&gt;/&lt;unitId&gt;/fault/image (retained PNG): the hardcopy
 * of the error screen of the SolvisControl, if the fault is shown as message</li>
 * </ul>
 * The notification itself is left to the smart home system.
 */
public class FaultPublisher implements IObserver<ErrorState.Info> {

	private static final ILogger logger = LogManager.getInstance().getLogger(FaultPublisher.class);

	private final Solvis solvis;
	private boolean active = false;
	private long since = 0L;
	private String message = null;
	private boolean imagePublished = false;
	Consumer<ISendData> publisher;

	public FaultPublisher(final Solvis solvis) {
		this.solvis = solvis;
		this.publisher = data -> solvis.getDistributor().notify(data);
	}

	@Override
	public void update(final ErrorState.Info info, final Object source) {
		if (info == null) {
			return; // only the visibility of the message changed
		}
		boolean imageNow = false;
		synchronized (this) {
			boolean nowActive = !info.isCleared();
			if (nowActive != this.active || this.since == 0L) {
				this.since = System.currentTimeMillis();
			}
			this.active = nowActive;
			this.message = info.getMessage();
			if (nowActive && info.getImage() != null) {
				imageNow = true;
			}
		}
		if (imageNow) {
			this.publishImage(info.getImage());
		}
		this.publisher.accept(this.getSendData());
	}

	private void publishImage(final MyImage image) {
		byte[] png;
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			image.writeWhole(out);
			png = out.toByteArray();
		} catch (IOException e) {
			logger.error("Error screen couldn't be encoded for MQTT: " + e.getMessage());
			return;
		}
		synchronized (this) {
			this.imagePublished = true;
		}
		this.publisher.accept(new ImagePackage(this.solvis, png));
	}

	/** current state, also published on every (re)connect */
	public ISendData getSendData() {
		return new UnitStatePackage(TopicType.UNIT_FAULT, this.solvis, this.toJson());
	}

	public synchronized boolean isActive() {
		return this.active;
	}

	public synchronized String toJson() {
		StringBuilder builder = new StringBuilder();
		builder.append("{\"state\":\"").append(this.active ? "on" : "off").append('"');
		builder.append(",\"since\":");
		if (this.since > 0L) {
			builder.append('"').append(Instant.ofEpochMilli(this.since).toString()).append('"');
		} else {
			builder.append("null");
		}
		builder.append(",\"message\":");
		if (this.message == null) {
			builder.append("null");
		} else {
			builder.append('"').append(escape(this.message)).append('"');
		}
		builder.append(",\"channels\":[");
		boolean first = true;
		for (String channel : this.errorChannels()) {
			if (!first) {
				builder.append(',');
			}
			first = false;
			builder.append('"').append(escape(channel)).append('"');
		}
		builder.append(']');
		builder.append(",\"message_screen\":").append(this.solvis.getSolvisState().isMessageError());
		builder.append(",\"image\":").append(this.active && this.imagePublished);
		builder.append('}');
		return builder.toString();
	}

	private Collection<String> errorChannels() {
		java.util.List<String> names = new ArrayList<>();
		for (String id : this.solvis.getSolvisState().getErrorChannelIds()) {
			String name = id;
			if (this.solvis.getAllSolvisData().get(id) != null) {
				name = this.solvis.getAllSolvisData().get(id).getName();
			}
			names.add(name);
		}
		Collections.sort(names);
		return names;
	}

	private static String escape(final String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
	}

	/** PNG of the error screen */
	static class ImagePackage implements ISendData {
		private final Solvis solvis;
		private final byte[] png;

		ImagePackage(final Solvis solvis, final byte[] png) {
			this.solvis = solvis;
			this.png = png;
		}

		byte[] getPng() {
			return this.png;
		}

		@Override
		public JsonPackage createJsonPackage() {
			return null;
		}

		@Override
		public Collection<MqttData> createMqttData() {
			return Collections
					.singletonList(new MqttData(TopicType.UNIT_FAULT_IMAGE, this.solvis, null, this.png, 0, true));
		}
	}
}
