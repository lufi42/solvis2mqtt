/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamException;

import org.eclipse.paho.client.mqttv3.MqttException;

import de.sgollmer.solvismax.Constants.ExitCodes;
import de.sgollmer.solvismax.connection.CommandHandler;
import de.sgollmer.solvismax.connection.mqtt.Mqtt;
import de.sgollmer.solvismax.crypt.CryptAes;
import de.sgollmer.solvismax.error.AliasException;
import de.sgollmer.solvismax.error.AssignmentException;
import de.sgollmer.solvismax.error.FileException;
import de.sgollmer.solvismax.error.LearningException;
import de.sgollmer.solvismax.error.MqttConnectionLost;
import de.sgollmer.solvismax.error.SolvisErrorException;
import de.sgollmer.solvismax.error.TerminationException;
import de.sgollmer.solvismax.error.TypeException;
import de.sgollmer.solvismax.helper.AbortHelper;
import de.sgollmer.solvismax.helper.Helper;
import de.sgollmer.solvismax.log.LogManager;
import de.sgollmer.solvismax.log.LogManager.ILogger;
import de.sgollmer.solvismax.log.LogManager.Level;
import de.sgollmer.solvismax.log.LogManager.LogErrors;
import de.sgollmer.solvismax.model.Instances;
import de.sgollmer.solvismax.xml.BaseControlFileReader;
import de.sgollmer.xmllibrary.XmlException;

/**
 * Entry point of solvis2mqtt.
 *
 * Arguments (each starting with "--"):
 *
 * <pre>
 *   --base-xml=&lt;path&gt;               base.xml to use (default: next to the jar)
 *   --string-to-crypt=&lt;text&gt;         print the encrypted form of a password and exit
 *   --server-learn                       learn the screens of the Solvis unit, then start
 *   --documentation [--csvSemicolon]     write the channel documentation of the configured units and exit
 *   --channels_of_all_configurations     write the channels of all possible configurations and exit
 * </pre>
 *
 * The server stops on SIGTERM (shutdown hook) or on the MQTT server command
 * TERMINATE (exit code 0). The server command RESTART ends the process with
 * exit code {@link ExitCodes#RESTART_REQUESTED}; the service manager
 * (Docker restart policy, systemd Restart=) starts it again.
 */
public class Main {

	private static final ILogger logger = LogManager.getInstance().getLogger(Main.class);

	public static Main getInstance() {
		Main main = MainHolder.INSTANCE;
		return main;
	}

	private static class MainHolder {

		private static final Main INSTANCE = new Main();
	}

	private static final Pattern cmdPattern = Pattern.compile("--([^=]*)(=(.*)){0,1}");
	private String startTime = null;
	private boolean shutdownExecuted = false;
	private Instances instances = null;
	private CommandHandler commandHandler = null;
	private Mqtt mqtt = null;

	private enum ExecutionMode {
		STANDARD(true, false), //
		LEARN(false, false), //
		DOCUMENTATION_OF_UNIT(false, true), //
		CHANNELS_OF_ALL_CONFIGURATIONS(false, false);

		private final boolean start;
		private final boolean mustLearned;

		private ExecutionMode(final boolean start, final boolean mustLearned) {
			this.start = start;
			this.mustLearned = mustLearned;
		}

		public boolean isStart() {
			return this.start;
		}

		boolean mustLearned() {
			return this.mustLearned;
		}
	}

	private void execute(final String[] args) {

		String baseXml = null;

		Collection<String> argCollection = splitArgs(args);

		for (Iterator<String> it = argCollection.iterator(); it.hasNext();) {
			String arg = it.next();
			Matcher matcher = cmdPattern.matcher(arg);
			if (!matcher.matches()) {
				System.err.println("Unknown argument: " + arg);
			} else {

				String command = matcher.group(1);
				String value = null;
				if (matcher.groupCount() >= 3) {
					value = matcher.group(3);
				}

				boolean found = true;

				switch (command) {
					case "string-to-crypt": // encrypt a password for base.xml
						if (value == null) {
							System.err.println("To less arguments!");
							System.exit(Constants.ExitCodes.ARGUMENT_FAIL);
						}
						try {
							String out = new CryptAes().encrypt(value);
							System.out.println(out);
							System.exit(Constants.ExitCodes.OK);
						} catch (Throwable e) {
							System.err.println(e.getMessage());
							System.exit(Constants.ExitCodes.CRYPTION_FAIL);
						}
						break;
					case "base-xml": // path of base.xml
						baseXml = value;
						break;
					default:
						found = false;
				}
				if (found) {
					it.remove();
				}
			}
		}

		BaseData baseData = null;
		LogManager logManager = LogManager.getInstance();
		try {
			baseData = new BaseControlFileReader(baseXml).read();
			if (baseData == null) {
				throw new XmlException("");
			}
		} catch (IOException | XmlException | XMLStreamException e) {
			e.printStackTrace();
			logger.log(Level.FATAL, "base.xml couldn't be read.", null, ExitCodes.READING_CONFIGURATION_FAIL);
			LogManager.exit(ExitCodes.READING_CONFIGURATION_FAIL);
		}

		String path = baseData.getWritablePath();

		LogErrors error = LogErrors.INIT;

		try {
			error = logManager.createInstance(path);
		} catch (IOException | FileException e) {
			error = LogErrors.INIT;
			e.printStackTrace();
		}

		if (error == LogErrors.INIT) {
			System.err.println("Log4j couldn't initalized");
		} else if (error == LogErrors.PREVIOUS) {
			LogManager.exit(0);
		}

		Level.getLevel("LEARN");

		ExecutionMode executionMode = ExecutionMode.STANDARD;
		boolean semicolon = false;

		for (Iterator<String> it = argCollection.iterator(); it.hasNext();) {
			String arg = it.next();
			Matcher matcher = cmdPattern.matcher(arg);
			if (!matcher.matches()) {
				System.err.println("Unknown argument: " + arg);
			} else {

				String command = matcher.group(1);

				switch (command) {
					case "server-learn":
						executionMode = ExecutionMode.LEARN;
						break;
					case "documentation":
						executionMode = ExecutionMode.DOCUMENTATION_OF_UNIT;
						break;
					case "channels_of_all_configurations":
						executionMode = ExecutionMode.CHANNELS_OF_ALL_CONFIGURATIONS;
						break;
					case "csvSemicolon":
						semicolon = true;
						break;
					default:
						System.err.println("Unknown argument: " + arg);
						System.exit(Constants.ExitCodes.ARGUMENT_FAIL);
						break;
				}
			}
		}

		Runnable runnable = new Runnable() {

			@Override
			public void run() {
				Main.this.shutDownHandling(true);
			}
		};

		Runtime.getRuntime().addShutdownHook(new Thread(runnable));

		try {
			this.instances = new Instances(baseData, executionMode == ExecutionMode.LEARN);
		} catch (Throwable e) {
			logger.error("Exception on reading configuration occured, cause:", e);
			e.printStackTrace();
			System.exit(ExitCodes.READING_CONFIGURATION_FAIL);
		}

		if (executionMode.mustLearned() && this.instances.mustLearn()) {
			System.err.println("Error: Learning is necessarry!");
			System.exit(ExitCodes.LEARNING_NECESSARY);
		}

		try {
			switch (executionMode) {
				case CHANNELS_OF_ALL_CONFIGURATIONS:
					this.instances.createCsvOut(semicolon);
					System.exit(ExitCodes.OK);
					break;

				case DOCUMENTATION_OF_UNIT:
					this.instances.createCurrentDocumentation(semicolon);
					System.exit(ExitCodes.OK);
					break;
				default:
					break;
			}
		} catch (IOException | XMLStreamException | LearningException | AliasException | TypeException
				| XmlException e) {
			System.out.flush();
			try {
				Thread.sleep(100);
			} catch (InterruptedException e1) {
			}
			logger.error("Exception on reading configuration or learning files occured, cause:", e);
			e.printStackTrace();
			System.exit(ExitCodes.READING_CONFIGURATION_FAIL);
		}

		try {
			this.waitForValidTime();
		} catch (TerminationException e1) {
			System.exit(ExitCodes.OK);
		}

		String serverStart = "Server started, Version " + Version.getInstance().getVersion();
		if (Version.getInstance().getBuildDate() != null) {
			serverStart += ", compiled at " + Version.getInstance().getBuildDate();
		}

		logger.info(serverStart);

		SimpleDateFormat format = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
		this.startTime = format.format(new Date());

		if (executionMode == ExecutionMode.LEARN || this.instances.mustLearn()) {

			try {
				this.instances.learn(executionMode == ExecutionMode.LEARN);
			} catch (IOException | XMLStreamException | LearningException | FileException e) {
				logger.error("Exception on reading configuration or learning files occured, cause:", e);
				e.printStackTrace();
				System.exit(ExitCodes.READING_CONFIGURATION_FAIL);
			} catch (TerminationException e) {
				System.exit(ExitCodes.OK);
			} catch (SolvisErrorException e) {
				logger.error("Solvis unit error occured while learning");
				System.exit(ExitCodes.SOLVIS_UNIT_ERROR);
			}
		}

		try {
			this.instances.init();

			if (executionMode.isStart()) {
				this.instances.start();
			} else {
				System.exit(ExitCodes.OK);
			}
		} catch (IOException | AssignmentException | XMLStreamException | AliasException | TypeException e) {
			logger.error("Exception on reading configuration occured, cause:", e);
			e.printStackTrace();
			System.exit(ExitCodes.READING_CONFIGURATION_FAIL);
		} catch (LearningException e2) {
			logger.error(e2.getMessage());
			System.exit(ExitCodes.LEARNING_NECESSARY);
		}

		Constants.Debug.logDebugging(logger);

		this.commandHandler = new CommandHandler(this.instances);
		this.mqtt = baseData.getMqtt();
		if (this.mqtt == null || !this.mqtt.isEnable()) {
			logger.error("Error: Mqtt is not configured or not enabled in base.xml, nothing to do.");
			System.exit(Constants.ExitCodes.MQTT_ERROR);
		}
		try {
			this.mqtt.connect(this.instances, this.commandHandler);
		} catch (MqttException e) {
			logger.error("Error: Mqtt connection error", e);
			System.exit(Constants.ExitCodes.MQTT_ERROR);
		}

		this.instances.initialized();

		System.out.println(serverStart);

	}

	/**
	 * Splits arguments which were passed as one string ("--a --b=1"), e.g. by
	 * the Windows task scheduler, into single arguments.
	 */
	private Collection<String> splitArgs(final String[] args) {
		Collection<String> argCollection = new ArrayList<>();
		for (String argO : args) {
			String[] comp = argO.split("--");

			for (String argI : comp) {
				argI = argI.trim();
				if (!argI.isEmpty()) {
					argCollection.add("--" + argI);
				}
			}
		}
		return argCollection;
	}

	void shutDownHandling(final boolean out) {
		if (this.shutdownExecuted) {
			return;
		}
		this.shutdownExecuted = true;
		AbortHelper.getInstance().abort();
		if (this.instances != null) {
			this.instances.abort();
		}
		if (this.commandHandler != null) {
			this.commandHandler.abort();
		}
		if (this.mqtt != null && this.mqtt.isEnable()) {
			try {
				this.mqtt.deleteRetainedTopics();
			} catch (MqttException | MqttConnectionLost e) {
				logger.error("Error on deleting Mqtt retained topics", e);
			}
			this.mqtt.abort();
		}

		Helper.Runnable.shutdown();

		if (out) {
			logger.info("Server terminated (started at " + Main.this.startTime + ")");
		}
	}

	public static void main(final String[] args) {
		Main main = Main.getInstance();
		try {

			main.execute(args);
		} catch (Throwable e) {
			main.shutDownHandling(true);
		}
	}

	private void waitForValidTime() throws TerminationException {
		long timeOfLastBackup = this.instances.getBackupHandler().getTimeOfLastBackup();
		int waitTime = 1000;
		boolean waiting = false;
		int currentWaitTime = 0;
		for (; currentWaitTime < Constants.MAX_WAIT_TIME_ON_STARTUP
				&& timeOfLastBackup > System.currentTimeMillis(); currentWaitTime += waitTime) {
			if (!waiting) {
				waiting = true;
				logger.info("Waiting for valid system time");
			}
			AbortHelper.getInstance().sleep(waitTime);
		}
		if (waiting) {
			logger.info("Valid system time detected after " + currentWaitTime / 1000 + "s.");
		}
	}
}
