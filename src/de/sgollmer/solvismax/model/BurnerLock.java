/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import de.sgollmer.solvismax.Constants;
import de.sgollmer.solvismax.connection.ISendData;
import de.sgollmer.solvismax.connection.UnitStatePackage;
import de.sgollmer.solvismax.connection.mqtt.TopicType;
import de.sgollmer.solvismax.error.TypeException;
import de.sgollmer.solvismax.log.LogManager;
import de.sgollmer.solvismax.log.LogManager.ILogger;
import de.sgollmer.solvismax.model.command.CommandControl;
import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.Observer.IObserver;
import de.sgollmer.solvismax.model.objects.data.SingleData;
import de.sgollmer.solvismax.model.objects.data.SolvisData;
import de.sgollmer.solvismax.model.objects.data.StringData;

/**
 * Burner lock via the analog output O-1 (issue #18). The SolvisControl
 * requests an external boiler (SolvisLino) with 0-10 V on O-1. Setting the
 * output to manual mode ("Hand", preset 0.0 V in the installer menu) blocks
 * the burner, e.g. to prefer the solar yield or during maintenance.
 * <p>
 * The lock is a time-limited unit state like the {@link InstallerMode}:
 * <ul>
 * <li>BURNER_LOCK_ENABLE[:minutes] writes the channel {@value #CHANNEL_ID}
 * (type of O-1) to {@value #LOCK_MODE}, BURNER_LOCK_DISABLE and the expiry
 * write it back to {@value #RELEASE_MODE}.</li>
 * <li>The expiry is persisted
 * (SolvisServerData/burner-lock-&lt;unitId&gt;.properties). After a restart
 * the lock is continued or, if it expired meanwhile, released.</li>
 * <li>The state is published retained on
 * &lt;topicPrefix&gt;/&lt;unitId&gt;/burner_lock as JSON:
 * <code>{"state":"on","remaining_s":5400,"until":"...","typ":"Hand"}</code>,
 * "typ" being the last value read from the plant.</li>
 * </ul>
 * The channel is an installer setting; the lock writes it without the
 * installer mode, because it is the only purpose of the channel. The preset
 * of the manual mode ("Vorgabe Hand") must be 0.0 V, it is not touched.
 */
public class BurnerLock implements IObserver<SolvisData> {

	private static final ILogger logger = LogManager.getInstance().getLogger(BurnerLock.class);

	/** channel of the type of the output O-1 (control.xml) */
	public static final String CHANNEL_ID = "C50";
	public static final String LOCK_MODE = "Hand";
	public static final String RELEASE_MODE = "Auto";

	private static final String KEY_UNTIL = "until";

	private final Solvis solvis;
	private File file = null;
	private long until = 0L;
	private String lastMode = null;
	private ScheduledFuture<?> timer = null;
	private ScheduledExecutorService scheduler = null;
	private boolean started = false;

	/** queues the write of the O-1 type, replaceable for tests */
	Consumer<String> writer;
	Consumer<ISendData> publisher;

	BurnerLock(final Solvis solvis) {
		this.solvis = solvis;
		this.writer = this::write;
		this.publisher = data -> solvis.getDistributor().notify(data);
	}

	/**
	 * @return true if the unit has the channel of the O-1 type (feature Admin and
	 *         a configuration with the installer screens of the outputs)
	 */
	public boolean isAvailable() {
		ChannelDescription description = this.getDescription();
		return description != null && this.solvis.isAdmin() && description.isWriteable();
	}

	private ChannelDescription getDescription() {
		return this.solvis.getChannelDescription(CHANNEL_ID);
	}

	/**
	 * Called once after the worker threads are started: restores the persisted
	 * lock or releases an expired one.
	 */
	void start() {
		synchronized (this) {
			if (this.started) {
				return;
			}
			this.started = true;
		}
		if (!this.isAvailable()) {
			if (this.getFile().exists()) {
				logger.warn("Burner lock file <" + this.getFile().getPath()
						+ "> exists, but the burner lock isn't available in this configuration. O-1 may still be in manual mode!");
			}
			return;
		}
		SolvisData data = this.solvis.getAllSolvisData().get(this.getDescription());
		if (data != null) {
			data.registerContinuousObserver(this);
		}
		long persisted = this.load();
		if (persisted <= 0L) {
			return;
		}
		long remaining = persisted - System.currentTimeMillis();
		if (remaining > 0L) {
			synchronized (this) {
				this.until = persisted;
				this.arm(remaining);
			}
			logger.warn("Burner lock of unit <" + this.solvis.getUnit().getId() + "> continued after restart, "
					+ (remaining + 59999L) / 60000L + " min left.");
			this.writer.accept(LOCK_MODE);
		} else {
			logger.warn("Burner lock of unit <" + this.solvis.getUnit().getId()
					+ "> expired while the server was down, O-1 is set back to " + RELEASE_MODE + ".");
			this.store(0L);
			this.writer.accept(RELEASE_MODE);
		}
		this.publish();
	}

	public synchronized boolean isActive() {
		return this.until > System.currentTimeMillis();
	}

	/** @return remaining time in ms, 0 if not active */
	public synchronized long getRemaining_ms() {
		return Math.max(0L, this.until - System.currentTimeMillis());
	}

	/** last value of the O-1 type read from the plant, null if unknown */
	public String getCurrentMode() {
		ChannelDescription description = this.getDescription();
		if (description == null) {
			return null;
		}
		SolvisData data = this.solvis.getAllSolvisData().get(description);
		if (data == null) {
			return null;
		}
		SingleData<?> single = data.getSingleData();
		return single == null ? null : single.toString();
	}

	/**
	 * Lock the burner (or extend the lock) for the given time.
	 */
	public void enable(final long duration_ms) {
		if (!this.isAvailable()) {
			logger.error("Burner lock not available: feature Admin and the channel <" + CHANNEL_ID
					+ "> (type of output O-1) are required.");
			return;
		}
		long until;
		synchronized (this) {
			until = System.currentTimeMillis() + Math.max(1L, duration_ms);
			this.until = until;
			this.arm(duration_ms);
		}
		this.store(until);
		logger.warn("Burner lock of unit <" + this.solvis.getUnit().getId() + "> enabled for "
				+ (duration_ms + 59999L) / 60000L + " min: output O-1 is set to " + LOCK_MODE
				+ " (installer setting).");
		this.writer.accept(LOCK_MODE);
		this.publish();
	}

	public void disable() {
		this.release("disabled");
	}

	private void expired() {
		synchronized (this) {
			if (this.isActive()) {
				return; // extended meanwhile
			}
		}
		this.release("expired");
	}

	private void release(final String reason) {
		boolean wasActive;
		synchronized (this) {
			wasActive = this.until > 0L;
			this.until = 0L;
			if (this.timer != null) {
				this.timer.cancel(false);
				this.timer = null;
			}
		}
		this.store(0L);
		if (wasActive) {
			logger.info("Burner lock of unit <" + this.solvis.getUnit().getId() + "> " + reason + ", output O-1 is set to "
					+ RELEASE_MODE + ".");
		}
		if (wasActive || LOCK_MODE.equals(this.getCurrentMode())) {
			this.writer.accept(RELEASE_MODE);
		}
		this.publish();
	}

	private void arm(final long delay_ms) {
		if (this.timer != null) {
			this.timer.cancel(false);
		}
		this.timer = this.getScheduler().schedule(this::expired, Math.max(1L, delay_ms), TimeUnit.MILLISECONDS);
	}

	private void write(final String mode) {
		ChannelDescription description = this.getDescription();
		if (description == null) {
			return;
		}
		try {
			StringData value = new StringData(mode, System.currentTimeMillis());
			CommandControl command = new CommandControl(description, value, this.solvis);
			if (this.solvis.getFeatures().isInteractiveGUIAccess()) {
				command.setWriteGeneration(this.solvis.getWriteStatus().requested(description, value));
			}
			this.solvis.execute(command);
		} catch (TypeException e) {
			logger.error("Burner lock: value <" + mode + "> of channel <" + CHANNEL_ID + "> is invalid.", e);
		}
	}

	/** value of the O-1 type read from the plant: publish on change */
	@Override
	public void update(final SolvisData data, final Object source) {
		String mode = this.getCurrentMode();
		boolean changed;
		synchronized (this) {
			changed = mode == null ? this.lastMode != null : !mode.equals(this.lastMode);
			this.lastMode = mode;
		}
		if (changed) {
			this.publish();
		}
	}

	/** Server stop: the lock stays in the plant and in the file. */
	void abort() {
		synchronized (this) {
			if (this.timer != null) {
				this.timer.cancel(false);
				this.timer = null;
			}
			if (this.scheduler != null) {
				this.scheduler.shutdownNow();
				this.scheduler = null;
			}
		}
	}

	public ISendData getSendData() {
		return new UnitStatePackage(TopicType.UNIT_BURNER_LOCK, this.solvis, this.toJson());
	}

	public String toJson() {
		long remaining;
		long until;
		synchronized (this) {
			remaining = this.getRemaining_ms();
			until = this.until;
		}
		String mode = this.getCurrentMode();
		StringBuilder builder = new StringBuilder();
		builder.append("{\"state\":\"").append(remaining > 0 ? "on" : "off").append('"');
		builder.append(",\"remaining_s\":").append((remaining + 999L) / 1000L);
		builder.append(",\"until\":");
		if (remaining > 0) {
			builder.append('"').append(Instant.ofEpochMilli(until).toString()).append('"');
		} else {
			builder.append("null");
		}
		builder.append(",\"typ\":");
		if (mode == null) {
			builder.append("null");
		} else {
			builder.append('"').append(mode.replace("\"", "\\\"")).append('"');
		}
		builder.append(",\"locked\":").append(LOCK_MODE.equals(mode));
		builder.append('}');
		return builder.toString();
	}

	private void publish() {
		this.publisher.accept(this.getSendData());
	}

	/** the unit is not known while the Solvis object is constructed */
	synchronized File getFile() {
		if (this.file == null) {
			File dir = new File(this.solvis.getWritePath(), Constants.Files.RESOURCE_DESTINATION);
			this.file = new File(dir, Constants.Files.BURNER_LOCK_PREFIX + this.solvis.getUnit().getId()
					+ Constants.Files.PENDING_RESTORE_SUFFIX);
		}
		return this.file;
	}

	private long load() {
		if (!this.getFile().exists()) {
			return 0L;
		}
		Properties properties = new Properties();
		try (InputStream in = new FileInputStream(this.file)) {
			properties.load(in);
			return Long.parseLong(properties.getProperty(KEY_UNTIL, "0").trim());
		} catch (IOException | NumberFormatException e) {
			logger.error("Burner lock file <" + this.getFile().getPath() + "> couldn't be read.", e);
			return 0L;
		}
	}

	private void store(final long until) {
		try {
			if (until <= 0L) {
				if (this.getFile().exists() && !this.getFile().delete()) {
					logger.warn("Burner lock file <" + this.getFile().getPath() + "> couldn't be deleted.");
				}
				return;
			}
			File dir = this.getFile().getParentFile();
			if (dir != null && !dir.exists()) {
				dir.mkdirs();
			}
			Properties properties = new Properties();
			properties.setProperty(KEY_UNTIL, Long.toString(until));
			try (OutputStream out = new FileOutputStream(this.file)) {
				properties.store(out, "Burner lock (output O-1 in manual mode) active until this time (ms since epoch, "
						+ Instant.ofEpochMilli(until) + "). Deleted by the server when the lock is released.");
			}
		} catch (IOException e) {
			logger.error("Burner lock file <" + this.getFile().getPath() + "> couldn't be written.", e);
		}
	}

	private synchronized ScheduledExecutorService getScheduler() {
		if (this.scheduler == null) {
			this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "BurnerLock-" + this.solvis.getUnit().getId());
				thread.setDaemon(true);
				return thread;
			});
		}
		return this.scheduler;
	}
}
