/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.model.command;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import de.sgollmer.solvismax.Constants;
import de.sgollmer.solvismax.error.TypeException;
import de.sgollmer.solvismax.log.LogManager;
import de.sgollmer.solvismax.log.LogManager.ILogger;
import de.sgollmer.solvismax.model.Solvis;
import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.data.SingleData;
import de.sgollmer.solvismax.model.objects.data.StringData;

/**
 * Channels which a dependency sequence has changed temporarily and which still
 * have to be restored to their original value.
 *
 * The entries are persisted in the data directory
 * (SolvisServerData/pending-restore-&lt;unitId&gt;.properties) as soon as a
 * dependency is changed and removed as soon as it is restored. If the server
 * is killed in the middle of a sequence, the file survives and the original
 * values are written back on the next start, before anything else is done
 * with the GUI (see {@link #queueRestores()}).
 */
public class PendingRestore {

	private static final ILogger logger = LogManager.getInstance().getLogger(PendingRestore.class);

	private final Solvis solvis;
	private final File file;
	private final Properties properties = new Properties();

	public PendingRestore(final Solvis solvis) {
		this.solvis = solvis;
		File dir = new File(solvis.getWritePath(), Constants.Files.RESOURCE_DESTINATION);
		this.file = new File(dir, Constants.Files.PENDING_RESTORE_PREFIX + solvis.getUnit().getId()
				+ Constants.Files.PENDING_RESTORE_SUFFIX);
		this.load();
	}

	private synchronized void load() {
		if (!this.file.exists()) {
			return;
		}
		try (InputStream in = new FileInputStream(this.file)) {
			this.properties.load(in);
		} catch (IOException e) {
			logger.error("Pending restore file <" + this.file.getPath() + "> couldn't be read.", e);
		}
	}

	private synchronized void store() {
		try {
			if (this.properties.isEmpty()) {
				if (this.file.exists() && !this.file.delete()) {
					logger.warn("Pending restore file <" + this.file.getPath() + "> couldn't be deleted.");
				}
				return;
			}
			File dir = this.file.getParentFile();
			if (dir != null && !dir.exists()) {
				dir.mkdirs();
			}
			try (OutputStream out = new FileOutputStream(this.file)) {
				this.properties.store(out, "Channels changed by a dependency sequence, to be restored. "
						+ "Deleted by the server as soon as the restore is done.");
			}
		} catch (IOException e) {
			logger.error("Pending restore file <" + this.file.getPath() + "> couldn't be written.", e);
		}
	}

	/**
	 * Remember the original value of a channel which is going to be changed by a
	 * dependency.
	 */
	public synchronized void put(final ChannelDescription description, final SingleData<?> original) {
		if (original == null) {
			return;
		}
		String value;
		try {
			SingleData<?> normalized = description.normalize(original);
			value = normalized == null ? original.toString() : normalized.toString();
		} catch (TypeException e) {
			value = original.toString();
		}
		if (value == null) {
			return;
		}
		Object former = this.properties.setProperty(description.getId(), value);
		if (!value.equals(former)) {
			this.store();
		}
	}

	/**
	 * The channel is restored (or the restore is not necessary any more).
	 */
	public synchronized void remove(final ChannelDescription description) {
		if (this.properties.remove(description.getId()) != null) {
			this.store();
		}
	}

	public synchronized boolean isEmpty() {
		return this.properties.isEmpty();
	}

	/**
	 * Queue a write command for every channel left over from a previous run.
	 * Called once after the worker threads are started.
	 */
	public void queueRestores() {
		Map<String, String> entries;
		synchronized (this) {
			entries = new TreeMap<>();
			for (String key : this.properties.stringPropertyNames()) {
				entries.put(key, this.properties.getProperty(key));
			}
		}
		if (entries.isEmpty()) {
			return;
		}
		logger.warn("The previous run was interrupted in a dependency sequence. " + entries.size()
				+ " channel(s) will be restored to the original value: " + entries);
		for (Map.Entry<String, String> entry : entries.entrySet()) {
			ChannelDescription description = this.solvis.getChannelDescription(entry.getKey());
			if (description == null) {
				logger.error("Channel <" + entry.getKey()
						+ "> of the pending restore doesn't exist any more (configuration changed?), entry ignored.");
				this.properties.remove(entry.getKey());
				this.store();
				continue;
			}
			try {
				CommandControl command = new CommandControl(description,
						new StringData(entry.getValue(), System.currentTimeMillis()), this.solvis);
				command.markPendingRestore();
				this.solvis.execute(command);
			} catch (TypeException e) {
				logger.error("Value <" + entry.getValue() + "> of the pending restore of channel <" + entry.getKey()
						+ "> is invalid, entry ignored.", e);
				this.properties.remove(entry.getKey());
				this.store();
			}
		}
	}
}
