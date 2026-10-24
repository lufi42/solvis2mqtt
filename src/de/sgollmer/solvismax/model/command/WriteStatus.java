/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.model.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import de.sgollmer.solvismax.connection.ISendData;
import de.sgollmer.solvismax.connection.mqtt.MqttData;
import de.sgollmer.solvismax.connection.mqtt.TopicType;
import de.sgollmer.solvismax.connection.transfer.JsonPackage;
import de.sgollmer.solvismax.error.TypeException;
import de.sgollmer.solvismax.log.LogManager;
import de.sgollmer.solvismax.log.LogManager.ILogger;
import de.sgollmer.solvismax.model.Solvis;
import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.ChannelInstance;
import de.sgollmer.solvismax.model.objects.IChannelSource;
import de.sgollmer.solvismax.model.objects.data.SingleData;
import de.sgollmer.solvismax.model.objects.data.SolvisData;

/**
 * Status of the write commands received from the smart home system, one entry
 * per channel. Published retained on &lt;prefix&gt;/&lt;unit&gt;/&lt;channel&gt;/write
 * as JSON, so that a dashboard can show a running command and lock the input
 * until the value is confirmed by the SolvisControl.
 *
 * A write which failed in all attempts within the command queue is not
 * dropped, it is queued again with increasing delays until it succeeds or a
 * newer command of the same channel replaces it.
 */
public class WriteStatus {

	private static final ILogger logger = LogManager.getInstance().getLogger(WriteStatus.class);

	static final long[] RETRY_DELAYS_MS = new long[] { 60000L, 120000L, 300000L, 600000L, 900000L };

	public enum State {
		IDLE("idle"), PENDING("pending"), RETRYING("retrying");

		private final String name;

		private State(final String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return this.name;
		}
	}

	private static class Entry {
		private State state = State.IDLE;
		private String target = null;
		private int attempts = 0;
		private int round = 0;
		private long since = 0L;
		private long nextRetry = 0L;
		private long generation = 0L;
	}

	private final Solvis solvis;
	private final Map<ChannelDescription, Entry> entries = new HashMap<>();
	private long generationCounter = 0L;
	private ScheduledExecutorService scheduler = null;

	long[] retryDelays = RETRY_DELAYS_MS;
	Consumer<CommandControl> executor;
	Consumer<ISendData> publisher;

	public WriteStatus(final Solvis solvis) {
		this.solvis = solvis;
		this.executor = command -> solvis.execute(command);
		this.publisher = data -> solvis.getDistributor().notify(data);
	}

	/**
	 * A write command of the smart home system was accepted and queued.
	 *
	 * @return generation of the command, a newer command of the same channel
	 *         invalidates it
	 */
	public long requested(final ChannelDescription description, final SingleData<?> value) {
		ISendData data;
		long generation;
		synchronized (this) {
			Entry entry = this.entry(description);
			entry.generation = ++this.generationCounter;
			entry.state = State.PENDING;
			entry.target = this.format(description, value);
			entry.attempts = 0;
			entry.round = 0;
			entry.since = System.currentTimeMillis();
			entry.nextRetry = 0L;
			generation = entry.generation;
			data = this.createSendData(description, entry);
		}
		this.publish(data);
		return generation;
	}

	/**
	 * One attempt of writing failed, the command stays in the queue.
	 */
	public void attemptFailed(final ChannelDescription description, final long generation) {
		ISendData data;
		synchronized (this) {
			Entry entry = this.current(description, generation);
			if (entry == null) {
				return;
			}
			++entry.attempts;
			entry.state = State.RETRYING;
			data = this.createSendData(description, entry);
		}
		this.publish(data);
	}

	/**
	 * The value was written and confirmed by the SolvisControl.
	 */
	public void succeeded(final ChannelDescription description, final long generation) {
		ISendData data;
		synchronized (this) {
			Entry entry = this.current(description, generation);
			if (entry == null) {
				return;
			}
			entry.state = State.IDLE;
			entry.since = System.currentTimeMillis();
			entry.nextRetry = 0L;
			data = this.createSendData(description, entry);
		}
		this.publish(data);
	}

	/**
	 * All attempts within the command queue failed. The write is queued again
	 * after a delay, unless a newer command of the channel arrives meanwhile.
	 */
	public void reschedule(final ChannelDescription description, final SingleData<?> value, final long generation) {
		ISendData data;
		long delay;
		synchronized (this) {
			Entry entry = this.current(description, generation);
			if (entry == null) {
				return;
			}
			delay = this.retryDelays[Math.min(entry.round, this.retryDelays.length - 1)];
			++entry.round;
			entry.state = State.RETRYING;
			entry.nextRetry = System.currentTimeMillis() + delay;
			data = this.createSendData(description, entry);
		}
		logger.warn("Set of channel <" + description.getId() + "> failed repeatedly, it will be tried again in "
				+ delay / 1000 + " s.");
		this.publish(data);
		this.getScheduler().schedule(() -> this.retry(description, value, generation), delay, TimeUnit.MILLISECONDS);
	}

	private void retry(final ChannelDescription description, final SingleData<?> value, final long generation) {
		synchronized (this) {
			if (this.current(description, generation) == null) {
				return;
			}
		}
		CommandControl command;
		try {
			command = new CommandControl(description, value, this.solvis);
		} catch (TypeException e) {
			logger.error("Retry of channel <" + description.getId() + "> not possible: " + e.getMessage());
			return;
		}
		command.setWriteGeneration(generation);
		this.executor.accept(command);
	}

	public synchronized boolean isPending(final ChannelDescription description) {
		Entry entry = this.entries.get(description);
		return entry != null && entry.state != State.IDLE;
	}

	synchronized State getState(final ChannelDescription description) {
		Entry entry = this.entries.get(description);
		return entry == null ? State.IDLE : entry.state;
	}

	synchronized int getAttempts(final ChannelDescription description) {
		Entry entry = this.entries.get(description);
		return entry == null ? 0 : entry.attempts;
	}

	/**
	 * @return the current status of all writeable channels which are sent to
	 *         the smart home system (idle if never written)
	 */
	public Collection<ISendData> getAll() {
		if (!this.solvis.getFeatures().isInteractiveGUIAccess()) {
			return Collections.emptyList();
		}
		Collection<ISendData> result = new ArrayList<>();
		synchronized (this) {
			for (SolvisData solvisData : this.solvis.getAllSolvisData().getSolvisDatas()) {
				if (solvisData.isDontSend() || solvisData.getSmartHomeData() == null) {
					continue;
				}
				ChannelInstance instance = solvisData.getChannelInstance();
				if (instance == null || !instance.isWriteable()
						|| instance.getDescription().getType() != IChannelSource.Type.CONTROL) {
					continue;
				}
				ChannelDescription description = solvisData.getDescription();
				Entry entry = this.entries.get(description);
				if (entry == null) {
					entry = new Entry();
				}
				result.add(this.createSendData(description, entry));
			}
		}
		return result;
	}

	private Entry entry(final ChannelDescription description) {
		Entry entry = this.entries.get(description);
		if (entry == null) {
			entry = new Entry();
			this.entries.put(description, entry);
		}
		return entry;
	}

	private Entry current(final ChannelDescription description, final long generation) {
		Entry entry = this.entries.get(description);
		if (entry == null || entry.generation != generation || generation == 0L) {
			return null;
		}
		return entry;
	}

	private String format(final ChannelDescription description, final SingleData<?> value) {
		if (value == null) {
			return null;
		}
		try {
			return description.normalize(value).toString();
		} catch (TypeException e) {
			return value.toString();
		}
	}

	private synchronized ScheduledExecutorService getScheduler() {
		if (this.scheduler == null) {
			this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "WriteRetry-" + this.solvis.getUnit().getId());
				thread.setDaemon(true);
				return thread;
			});
		}
		return this.scheduler;
	}

	private void publish(final ISendData data) {
		if (data != null) {
			this.publisher.accept(data);
		}
	}

	private ISendData createSendData(final ChannelDescription description, final Entry entry) {
		SolvisData solvisData = this.solvis.getAllSolvisData().get(description);
		if (solvisData == null) {
			return null;
		}
		return new Package(this.solvis, solvisData.getName(), toJson(entry));
	}

	static String toJson(final State state, final String target, final int attempts, final long since,
			final long nextRetry) {
		StringBuilder builder = new StringBuilder();
		builder.append("{\"state\":\"").append(state.toString()).append('"');
		builder.append(",\"target\":");
		if (target == null) {
			builder.append("null");
		} else {
			builder.append('"').append(target.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		}
		builder.append(",\"attempts\":").append(attempts);
		builder.append(",\"since\":");
		appendTime(builder, since);
		builder.append(",\"next_retry\":");
		appendTime(builder, nextRetry);
		builder.append('}');
		return builder.toString();
	}

	private static String toJson(final Entry entry) {
		return toJson(entry.state, entry.target, entry.attempts, entry.since, entry.nextRetry);
	}

	private static void appendTime(final StringBuilder builder, final long time) {
		if (time <= 0L) {
			builder.append("null");
		} else {
			builder.append('"').append(Instant.ofEpochMilli(time).toString()).append('"');
		}
	}

	static class Package implements ISendData {
		private final Solvis solvis;
		private final String channelName;
		private final String json;

		Package(final Solvis solvis, final String channelName, final String json) {
			this.solvis = solvis;
			this.channelName = channelName;
			this.json = json;
		}

		String getJson() {
			return this.json;
		}

		@Override
		public JsonPackage createJsonPackage() {
			return null;
		}

		@Override
		public Collection<MqttData> createMqttData() {
			return Collections.singletonList(
					new MqttData(TopicType.UNIT_CHANNEL_WRITE, this.solvis, this.channelName, this.json, 0, true));
		}
	}
}
