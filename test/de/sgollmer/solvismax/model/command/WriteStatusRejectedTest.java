package de.sgollmer.solvismax.model.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.connection.ISendData;
import de.sgollmer.solvismax.model.OfflineInstances;
import de.sgollmer.solvismax.model.Solvis;
import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.data.StringData;

/** Issue #25: a refused installer write is reported as "rejected" on the write topic. */
class WriteStatusRejectedTest {

	@Test
	void rejectedIsPublishedAndNotPending(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, true));
		WriteStatus status = solvis.getWriteStatus();
		List<ISendData> published = new ArrayList<>();
		status.publisher = published::add;

		ChannelDescription c47 = solvis.getChannelDescription("C47");
		status.rejected(c47, c47.interpretSetData(new StringData("10", 1L), false));

		assertEquals(WriteStatus.State.REJECTED, status.getState(c47));
		assertEquals(1, published.size());
		String json = ((WriteStatus.Package) published.get(0)).getJson();
		assertTrue(json.contains("\"state\":\"rejected\""), json);
		assertTrue(json.contains("\"target\":\"10\""), json);
		assertFalse(json.contains("\"state\":\"pending\""));
	}
}
