package de.sgollmer.solvismax.model.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.model.Instances;
import de.sgollmer.solvismax.model.OfflineInstances;
import de.sgollmer.solvismax.model.Solvis;
import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.data.IMode;
import de.sgollmer.solvismax.model.objects.data.SingleData;
import de.sgollmer.solvismax.model.objects.data.StringData;

class PendingRestoreTest {

	private static File fileOf(final File dir, final Solvis solvis) {
		return new File(new File(new File(dir, "data"), "SolvisServerData"),
				"pending-restore-" + solvis.getUnit().getId() + ".properties");
	}

	@Test
	void putPersistsOriginalValueAndRemoveDeletesTheFile(@TempDir final File dir) throws Exception {
		Instances instances = OfflineInstances.create(dir);
		Solvis solvis = OfflineInstances.firstUnit(instances);
		PendingRestore pending = solvis.getPendingRestore();
		assertNotNull(pending);
		assertTrue(pending.isEmpty());

		ChannelDescription c06 = solvis.getChannelDescription("C06");
		assertNotNull(c06, "C06 (Anlagenmodus_HK1) missing in the example configuration");
		IMode<?> mode = c06.getModes().iterator().next();
		SingleData<?> original = c06.interpretSetData(new StringData(mode.getName(), 1L), false);
		assertNotNull(original);

		pending.put(c06, original);

		File file = fileOf(dir, solvis);
		assertTrue(file.exists(), "pending restore file not written");
		Properties props = new Properties();
		try (InputStream in = new FileInputStream(file)) {
			props.load(in);
		}
		assertEquals(mode.getName(), props.getProperty("C06"));

		// a new instance (= next start) sees the entry
		PendingRestore reloaded = new PendingRestore(solvis);
		assertFalse(reloaded.isEmpty());

		pending.remove(c06);
		assertTrue(pending.isEmpty());
		assertFalse(file.exists(), "pending restore file not deleted after restore");
	}

	@Test
	void queueRestoresDropsUnknownChannelsAndInvalidValues(@TempDir final File dir) throws Exception {
		Instances instances = OfflineInstances.create(dir);
		Solvis solvis = OfflineInstances.firstUnit(instances);
		File file = fileOf(dir, solvis);
		file.getParentFile().mkdirs();
		Files.write(file.toPath(), "XYZ=1\nC12=no_such_mode\n".getBytes(StandardCharsets.ISO_8859_1));

		PendingRestore pending = new PendingRestore(solvis);
		assertFalse(pending.isEmpty());
		pending.queueRestores();

		assertTrue(pending.isEmpty(), "unknown channel and invalid value must be dropped");
		assertFalse(file.exists());
	}

	@Test
	void queueRestoresKeepsValidEntryUntilWritten(@TempDir final File dir) throws Exception {
		Instances instances = OfflineInstances.create(dir);
		Solvis solvis = OfflineInstances.firstUnit(instances);
		ChannelDescription c06 = solvis.getChannelDescription("C06");
		String modeName = c06.getModes().iterator().next().getName();
		File file = fileOf(dir, solvis);
		file.getParentFile().mkdirs();
		Files.write(file.toPath(), ("C06=" + modeName + "\n").getBytes(StandardCharsets.ISO_8859_1));

		PendingRestore pending = new PendingRestore(solvis);
		pending.queueRestores();

		// the entry stays until the write command has been executed against the unit
		assertFalse(pending.isEmpty());
		assertTrue(file.exists());
	}
}
