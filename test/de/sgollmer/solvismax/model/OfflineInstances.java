package de.sgollmer.solvismax.model;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import de.sgollmer.solvismax.BaseData;
import de.sgollmer.solvismax.Constants;
import de.sgollmer.solvismax.Main;
import de.sgollmer.solvismax.crypt.CryptAes;
import de.sgollmer.solvismax.xml.BaseControlFileReader;

/**
 * Test fixture: the configured units of the shipped example base.xml,
 * initialised offline (no Solvis unit, no MQTT broker), the same way the
 * documentation mode of the server does it. The data directory is created
 * below the given directory.
 */
public final class OfflineInstances {

	private OfflineInstances() {
	}

	public static Instances create(final File dir) throws Exception {
		File data = new File(dir, "data");
		if (!data.isDirectory() && !data.mkdirs()) {
			throw new IllegalStateException("Can't create " + data);
		}
		String path = data.getAbsolutePath().replace('\\', '/');

		String xml;
		try (InputStream in = Main.class.getResourceAsStream(Constants.Files.RESOURCE + "/base.xml")) {
			xml = new String(readAll(in), StandardCharsets.UTF_8);
		}
		String crypt = new CryptAes().encrypt("test");
		xml = xml.replaceAll("writablePathLinux=\"[^\"]*\"", "writablePathLinux=\"" + path + "\"");
		xml = xml.replaceAll("writeablePathWindows=\"[^\"]*\"", "writeablePathWindows=\"" + path + "\"");
		xml = xml.replace("passwordCrypt=\"AES-coded\"", "passwordCrypt=\"" + crypt + "\"");
		// the control channels (C*) only exist with GUI access
		xml = xml.replace("<tns:Feature id=\"InteractiveGUIAccess\" value=\"false\" />",
				"<tns:Feature id=\"InteractiveGUIAccess\" value=\"true\" />");

		File baseXml = new File(dir, "base.xml");
		Files.write(baseXml.toPath(), xml.getBytes(StandardCharsets.UTF_8));

		BaseData baseData = new BaseControlFileReader(baseXml.getAbsolutePath()).read();
		if (baseData == null) {
			throw new IllegalStateException("base.xml of the fixture is invalid");
		}
		Instances instances = new Instances(baseData, false);
		for (Solvis solvis : instances.getUnits()) {
			// without learned data the configuration mask has to be forced from the
			// unit definition, as the documentation mode of the server does
			solvis.getUnit().setForcedConfigMask(
					solvis.getUnit().getConfiguration().getConfigurationMask(solvis.getSolvisDescription()));
			solvis.init();
		}
		return instances;
	}

	public static Solvis firstUnit(final Instances instances) {
		return instances.getUnits().iterator().next();
	}

	private static byte[] readAll(final InputStream in) throws java.io.IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int n;
		while ((n = in.read(buffer)) > 0) {
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}
}
