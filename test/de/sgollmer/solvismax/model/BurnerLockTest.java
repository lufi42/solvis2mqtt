package de.sgollmer.solvismax.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.connection.ISendData;
import de.sgollmer.solvismax.connection.mqtt.MqttData;
import de.sgollmer.solvismax.error.TypeException;
import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.data.StringData;

/** Issue #18: burner lock via the type of the analog output O-1 (channel C50). */
class BurnerLockTest {

	@Test
	void channelOfTheOutputTypeIsAWritableInstallerSetting(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, true));
		ChannelDescription c50 = solvis.getChannelDescription(BurnerLock.CHANNEL_ID);
		assertNotNull(c50, "C50 missing");
		assertTrue(c50.isWriteable());
		assertTrue(solvis.isInstallerChannel(c50), "O-1 is reached via the installer menu");
		assertEquals("C50.Ausgang_O1_Typ", solvis.getAllSolvisData().get(c50).getName());
		assertNotNull(c50.interpretSetData(new StringData(BurnerLock.LOCK_MODE, 1L), false));
		assertNotNull(c50.interpretSetData(new StringData(BurnerLock.RELEASE_MODE, 1L), false));
		assertThrows(TypeException.class, () -> c50.interpretSetData(new StringData("Aus", 1L), false));
		assertTrue(solvis.getBurnerLock().isAvailable());
	}

	@Test
	void withoutAdminTheLockIsNotAvailable(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, false));
		assertFalse(solvis.getBurnerLock().isAvailable());
		solvis.getBurnerLock().enable(60000L);
		assertFalse(solvis.getBurnerLock().isActive(), "nothing happens without the channel");
	}

	@Test
	void lockIsWrittenPersistedAndReleased(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, true));
		BurnerLock lock = solvis.getBurnerLock();
		List<String> writes = new ArrayList<>();
		List<ISendData> published = new ArrayList<>();
		lock.writer = writes::add;
		lock.publisher = published::add;

		lock.start();
		assertTrue(writes.isEmpty(), "no lock file, nothing to do at start");
		assertFalse(lock.isActive());
		String idle = lock.toJson();
		assertTrue(idle.startsWith("{\"state\":\"off\""), idle);
		assertTrue(idle.contains("\"typ\":null"), idle);
		assertTrue(idle.contains("\"locked\":false"), idle);

		lock.enable(600L);
		assertTrue(lock.isActive());
		assertEquals(BurnerLock.LOCK_MODE, writes.get(0));
		assertTrue(lock.getFile().exists(), "expiry is persisted");
		String json = lock.toJson();
		assertTrue(json.startsWith("{\"state\":\"on\""), json);
		assertTrue(json.contains("\"until\":\""), json);
		MqttData data = lock.getSendData().createMqttData().iterator().next();
		assertEquals(solvis.getMqtt().getTopicPrefix() + "/" + solvis.getUnit().getId() + "/burner_lock",
				solvis.getMqtt().getTopic(data));
		assertFalse(published.isEmpty());

		Thread.sleep(1200L);
		assertFalse(lock.isActive(), "lock must expire on its own");
		assertEquals(BurnerLock.RELEASE_MODE, writes.get(writes.size() - 1));
		assertFalse(lock.getFile().exists(), "file deleted on release");

		lock.enable(60000L);
		assertTrue(lock.isActive());
		lock.disable();
		assertFalse(lock.isActive());
		assertEquals(BurnerLock.RELEASE_MODE, writes.get(writes.size() - 1));
		assertFalse(lock.getFile().exists());
		assertEquals(120 * 60000L, solvis.getUnit().getBurnerLockTime_ms(), "default from the example base.xml");
	}

	@Test
	void persistedLockIsContinuedOrReleasedAtStart(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, true));
		BurnerLock lock = solvis.getBurnerLock();
		List<String> writes = new ArrayList<>();
		lock.writer = writes::add;
		lock.publisher = data -> {
		};

		writeFile(lock.getFile(), System.currentTimeMillis() + 60000L);
		lock.start();
		assertTrue(lock.isActive(), "lock continued after restart");
		assertTrue(lock.getRemaining_ms() > 30000L);
		assertEquals(BurnerLock.LOCK_MODE, writes.get(0), "written again, the learning may have reset it");
		lock.abort();

		Solvis second = OfflineInstances.firstUnit(OfflineInstances.create(new File(dir, "second"), true));
		BurnerLock expired = second.getBurnerLock();
		List<String> writes2 = new ArrayList<>();
		expired.writer = writes2::add;
		expired.publisher = data -> {
		};
		writeFile(expired.getFile(), System.currentTimeMillis() - 1000L);
		expired.start();
		assertFalse(expired.isActive());
		assertEquals(BurnerLock.RELEASE_MODE, writes2.get(0), "expired while down: released");
		assertFalse(expired.getFile().exists());
	}

	private static void writeFile(final File file, final long until) throws Exception {
		file.getParentFile().mkdirs();
		Properties properties = new Properties();
		properties.setProperty("until", Long.toString(until));
		try (OutputStream out = new FileOutputStream(file)) {
			properties.store(out, "test");
		}
	}
}
