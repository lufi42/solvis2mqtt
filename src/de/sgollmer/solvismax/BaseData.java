/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax;

import java.io.IOException;

import javax.xml.namespace.QName;

import de.sgollmer.solvismax.connection.mqtt.Mqtt;
import de.sgollmer.solvismax.model.objects.unit.Units;
import de.sgollmer.xmllibrary.BaseCreator;
import de.sgollmer.xmllibrary.CreatorByXML;
import de.sgollmer.xmllibrary.XmlException;

/**
 * Content of base.xml: execution data, the Solvis units and the MQTT
 * connection.
 */
public class BaseData {

	private static final String XML_UNITS = "Units";
	private static final String XML_EXECUTION_DATA = "ExecutionData";
	private static final String XML_MQTT = "Mqtt";

	public static boolean DEBUG = false;

	private final String timeZone;
	private final String writeablePathWindows;
	private final String writablePathLinux;
	private final int echoInhibitTime_ms;
	private final Units units;
	private final Mqtt mqtt;

	private BaseData(final String timeZone, final String writeablePathWindows, final String writablePathLinux,
			final int echoInhibitTime_ms, final Units units, final Mqtt mqtt) {
		this.timeZone = timeZone;
		this.writeablePathWindows = writeablePathWindows;
		this.writablePathLinux = writablePathLinux;
		this.units = units;
		this.echoInhibitTime_ms = echoInhibitTime_ms;
		this.mqtt = mqtt;
	}

	public String getWritablePath() {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		return windows ? this.writeablePathWindows : this.writablePathLinux;
	}

	public String getTimeZone() {
		return this.timeZone;
	}

	public Units getUnits() {
		return this.units;
	}

	public int getEchoInhibitTime_ms() {
		return this.echoInhibitTime_ms;
	}

	public Mqtt getMqtt() {
		return this.mqtt;
	}

	public static class Creator extends BaseCreator<BaseData> {

		private Units units;
		private ExecutionData executionData;
		private Mqtt mqtt = null;

		public Creator(final String id) {
			super(id);
		}

		@Override
		public void setAttribute(final QName name, final String value) {
			switch (name.getLocalPart()) {
				case "DEBUG":
					DEBUG = Boolean.parseBoolean(value);
					break;
			}
		}

		@Override
		public BaseData create() throws XmlException, IOException {
			return new BaseData(this.executionData.timeZone, this.executionData.writeablePathWindows,
					this.executionData.writablePathLinux, this.executionData.echoInhibitTime_ms, this.units,
					this.mqtt);
		}

		@Override
		public CreatorByXML<?> getCreator(final QName name) {
			String id = name.getLocalPart();
			switch (id) {
				case XML_UNITS:
					return new Units.Creator(id, this.getBaseCreator());
				case XML_EXECUTION_DATA:
					return new ExecutionData.Creator(id, getBaseCreator());
				case XML_MQTT:
					return new Mqtt.Creator(id, getBaseCreator());
			}
			return null;
		}

		@Override
		public void created(final CreatorByXML<?> creator, final Object created) {
			switch (creator.getId()) {
				case XML_UNITS:
					this.units = (Units) created;
					break;
				case XML_EXECUTION_DATA:
					this.executionData = (ExecutionData) created;
					break;
				case XML_MQTT:
					this.mqtt = (Mqtt) created;
					break;
			}
		}
	}

	private static class ExecutionData {
		private final String timeZone;
		private final String writeablePathWindows;
		private final String writablePathLinux;
		private final int echoInhibitTime_ms;

		private ExecutionData(final String timeZone, final String writeablePathWindows,
				final String writablePathLinux, final int echoInhibitTime_ms) {
			this.timeZone = timeZone;
			this.writeablePathWindows = writeablePathWindows;
			this.writablePathLinux = writablePathLinux;
			this.echoInhibitTime_ms = echoInhibitTime_ms;
		}

		private static class Creator extends CreatorByXML<ExecutionData> {

			private String timeZone;
			private String writeablePathWindows;
			private String writablePathLinux;
			private int echoInhibitTime_ms;

			private Creator(final String id, final BaseCreator<?> creator) {
				super(id, creator);
			}

			@Override
			public void setAttribute(final QName name, final String value) {
				switch (name.getLocalPart()) {
					case "timeZone":
						this.timeZone = value;
						break;
					case "writeablePathWindows":
						this.writeablePathWindows = value;
						break;
					case "writablePathLinux":
						this.writablePathLinux = value;
						break;
					case "echoInhibitTime_ms":
						this.echoInhibitTime_ms = Integer.parseInt(value);
						break;
				}
			}

			@Override
			public ExecutionData create() throws XmlException, IOException {
				return new ExecutionData(this.timeZone, this.writeablePathWindows, this.writablePathLinux,
						this.echoInhibitTime_ms);
			}

			@Override
			public CreatorByXML<?> getCreator(final QName name) {
				return null;
			}

			@Override
			public void created(final CreatorByXML<?> creator, final Object created) {
			}
		}
	}
}
