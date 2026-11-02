/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.model;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import de.sgollmer.solvismax.connection.ISendData;
import de.sgollmer.solvismax.connection.UnitStatePackage;
import de.sgollmer.solvismax.connection.mqtt.TopicType;
import de.sgollmer.solvismax.log.LogManager;
import de.sgollmer.solvismax.log.LogManager.ILogger;

/**
 * Time-limited installer mode of a unit. Installer settings (channels in the
 * installer menu of the SolvisControl) are only written while the mode is
 * active, comparable to the installer code of the SolvisControl itself. The
 * mode switches itself off after the configured time and when the server
 * stops. The state is published retained on
 * &lt;topicPrefix&gt;/&lt;unitId&gt;/installer_mode as JSON:
 * <code>{"state":"on","remaining_s":840,"until":"2026-11-02T13:15:00Z"}</code>.
 */
public class InstallerMode {

	private static final ILogger logger = LogManager.getInstance().getLogger(InstallerMode.class);

	private final Solvis solvis;
	private long until = 0L;
	private ScheduledFuture<?> timer = null;
	private ScheduledExecutorService scheduler = null;

	InstallerMode(final Solvis solvis) {
		this.solvis = solvis;
	}

	public synchronized boolean isActive() {
		return this.until > System.currentTimeMillis();
	}

	/** @return remaining time in ms, 0 if not active */
	public synchronized long getRemaining_ms() {
		return Math.max(0L, this.until - System.currentTimeMillis());
	}

	/**
	 * Switch the mode on (or extend it) for the given time.
	 */
	public void enable(final long duration_ms) {
		synchronized (this) {
			this.until = System.currentTimeMillis() + Math.max(1L, duration_ms);
			if (this.timer != null) {
				this.timer.cancel(false);
			}
			this.timer = this.getScheduler().schedule(this::expired, duration_ms, TimeUnit.MILLISECONDS);
		}
		logger.warn("Installer mode of unit <" + this.solvis.getUnit().getId() + "> enabled for "
				+ (duration_ms + 59999L) / 60000L + " min: installer settings may be changed.");
		this.publish();
	}

	public void disable() {
		boolean wasActive;
		synchronized (this) {
			wasActive = this.isActive();
			this.until = 0L;
			if (this.timer != null) {
				this.timer.cancel(false);
				this.timer = null;
			}
		}
		if (wasActive) {
			logger.info("Installer mode of unit <" + this.solvis.getUnit().getId() + "> disabled.");
		}
		this.publish();
	}

	private void expired() {
		synchronized (this) {
			if (this.isActive()) {
				return; // extended meanwhile
			}
			this.until = 0L;
			this.timer = null;
		}
		logger.info("Installer mode of unit <" + this.solvis.getUnit().getId() + "> expired.");
		this.publish();
	}

	/** Server stop: the mode doesn't survive a restart. */
	void abort() {
		this.disable();
		synchronized (this) {
			if (this.scheduler != null) {
				this.scheduler.shutdownNow();
				this.scheduler = null;
			}
		}
	}

	public ISendData getSendData() {
		return new UnitStatePackage(TopicType.UNIT_INSTALLER_MODE, this.solvis, this.toJson());
	}

	public synchronized String toJson() {
		long remaining = this.getRemaining_ms();
		StringBuilder builder = new StringBuilder();
		builder.append("{\"state\":\"").append(remaining > 0 ? "on" : "off").append('"');
		builder.append(",\"remaining_s\":").append((remaining + 999L) / 1000L);
		builder.append(",\"until\":");
		if (remaining > 0) {
			builder.append('"').append(Instant.ofEpochMilli(this.until).toString()).append('"');
		} else {
			builder.append("null");
		}
		builder.append('}');
		return builder.toString();
	}

	private void publish() {
		this.solvis.getDistributor().notify(this.getSendData());
	}

	private synchronized ScheduledExecutorService getScheduler() {
		if (this.scheduler == null) {
			this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "InstallerMode-" + this.solvis.getUnit().getId());
				thread.setDaemon(true);
				return thread;
			});
		}
		return this.scheduler;
	}
}
