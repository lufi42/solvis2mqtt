/************************************************************************
 *
 * $Id$
 *
 *
 ************************************************************************/

package de.sgollmer.solvismax.model.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.connection.ISendData;
import de.sgollmer.solvismax.connection.mqtt.MqttData;
import de.sgollmer.solvismax.model.Instances;
import de.sgollmer.solvismax.model.OfflineInstances;
import de.sgollmer.solvismax.model.Solvis;
import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.data.IMode;
import de.sgollmer.solvismax.model.objects.data.SingleData;
import de.sgollmer.solvismax.model.objects.data.StringData;

class WriteStatusTest {

	private static class Fixture {
		final WriteStatus status;
		final List<String> published = new ArrayList<>();
		final List<CommandControl> executed = new CopyOnWriteArrayList<>();
		final ChannelDescription c06;
		final SingleData<?> value;
		final Solvis solvis;

		Fixture(final File dir) throws Exception {
			Instances instances = OfflineInstances.create(dir);
			this.solvis = OfflineInstances.firstUnit(instances);
			this.status = new WriteStatus(this.solvis);
			this.status.publisher = data -> this.published.add(((WriteStatus.Package) data).getJson());
			this.status.executor = command -> this.executed.add(command);
			this.c06 = this.solvis.getChannelDescription("C06");
			assertNotNull(this.c06, "C06 (Anlagenmodus_HK1) missing in the example configuration");
			IMode<?> mode = this.c06.getModes().iterator().next();
			this.value = this.c06.interpretSetData(new StringData(mode.getName(), 1L), false);
			assertNotNull(this.value);
		}

		String last() {
			return this.published.get(this.published.size() - 1);
		}
	}

	@Test
	void requestFailureAndSuccessArePublished(@TempDir final File dir) throws Exception {
		Fixture f = new Fixture(dir);

		long generation = f.status.requested(f.c06, f.value);
		assertEquals(WriteStatus.State.PENDING, f.status.getState(f.c06));
		assertTrue(f.last().startsWith("{\"state\":\"pending\""), f.last());
		assertTrue(f.status.isPending(f.c06));

		f.status.attemptFailed(f.c06, generation);
		assertEquals(WriteStatus.State.RETRYING, f.status.getState(f.c06));
		assertEquals(1, f.status.getAttempts(f.c06));
		assertTrue(f.last().contains("\"attempts\":1"), f.last());

		f.status.succeeded(f.c06, generation);
		assertEquals(WriteStatus.State.IDLE, f.status.getState(f.c06));
		assertFalse(f.status.isPending(f.c06));
		assertTrue(f.last().startsWith("{\"state\":\"idle\""), f.last());
	}

	@Test
	void olderCommandDoesNotChangeTheStatusOfANewerOne(@TempDir final File dir) throws Exception {
		Fixture f = new Fixture(dir);

		long first = f.status.requested(f.c06, f.value);
		long second = f.status.requested(f.c06, f.value);
		int count = f.published.size();

		// the first command is still executed (it was running) and succeeds
		f.status.succeeded(f.c06, first);
		f.status.attemptFailed(f.c06, first);
		assertEquals(count, f.published.size(), "a superseded command must not publish");
		assertEquals(WriteStatus.State.PENDING, f.status.getState(f.c06));

		f.status.succeeded(f.c06, second);
		assertEquals(WriteStatus.State.IDLE, f.status.getState(f.c06));
	}

	@Test
	void rescheduledWriteIsQueuedAgainUnlessSuperseded(@TempDir final File dir) throws Exception {
		Fixture f = new Fixture(dir);
		f.status.retryDelays = new long[] { 50L };

		long generation = f.status.requested(f.c06, f.value);
		f.status.reschedule(f.c06, f.value, generation);
		assertEquals(WriteStatus.State.RETRYING, f.status.getState(f.c06));
		assertTrue(f.last().contains("\"next_retry\":\""), f.last());
		waitFor(() -> !f.executed.isEmpty());
		assertEquals(1, f.executed.size());

		// superseded before the retry fires: nothing is queued
		f.executed.clear();
		long old = f.status.requested(f.c06, f.value);
		f.status.reschedule(f.c06, f.value, old);
		f.status.requested(f.c06, f.value);
		Thread.sleep(300L);
		assertTrue(f.executed.isEmpty(), "superseded retry must not be executed");
	}

	@Test
	void allWriteableControlChannelsAreReportedIdle(@TempDir final File dir) throws Exception {
		Fixture f = new Fixture(dir);
		Collection<ISendData> all = f.status.getAll();
		assertFalse(all.isEmpty());
		boolean c06Found = false;
		for (ISendData data : all) {
			MqttData mqttData = data.createMqttData().iterator().next();
			assertNotNull(mqttData);
			String json = ((WriteStatus.Package) data).getJson();
			assertTrue(json.startsWith("{\"state\":\"idle\""), json);
			c06Found |= mqttData.toString().contains("C06");
		}
		assertTrue(c06Found, "C06 must be reported");
	}

	@Test
	void jsonFormat() {
		assertEquals("{\"state\":\"retrying\",\"target\":\"5\\\"0\",\"attempts\":2,\"since\":\"1970-01-01T00:00:01Z\","
				+ "\"next_retry\":null}", WriteStatus.toJson(WriteStatus.State.RETRYING, "5\"0", 2, 1000L, 0L));
		assertEquals("{\"state\":\"idle\",\"target\":null,\"attempts\":0,\"since\":null,\"next_retry\":null}",
				WriteStatus.toJson(WriteStatus.State.IDLE, null, 0, 0L, 0L));
	}

	private interface Condition {
		boolean met();
	}

	private static void waitFor(final Condition condition) throws InterruptedException {
		long end = System.currentTimeMillis() + 5000L;
		while (!condition.met() && System.currentTimeMillis() < end) {
			Thread.sleep(20L);
		}
		assertTrue(condition.met(), "condition not met within 5 s");
	}
}
