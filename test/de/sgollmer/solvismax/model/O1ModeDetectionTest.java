package de.sgollmer.solvismax.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collection;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.sgollmer.solvismax.imagepatternrecognition.image.MyImage;
import de.sgollmer.solvismax.model.objects.ChannelDescription;
import de.sgollmer.solvismax.model.objects.control.ModeEntry;
import de.sgollmer.solvismax.model.objects.data.IMode;

/**
 * Issue #18: the MustBeWhite rectangles of C50 must identify the shown mode on
 * real screenshots of the SolvisControl 2 (test/resources, taken 07.11.2026).
 * The grey button background (RGB 119) is below the brightness threshold of
 * MyImage, so only truly white areas count.
 */
class O1ModeDetectionTest {

	private static final File RESOURCES = new File("test/resources");

	@Test
	void whiteRectanglesIdentifyAutoAndHandOnRealScreenshots(@TempDir final File dir) throws Exception {
		Solvis solvis = OfflineInstances.firstUnit(OfflineInstances.create(dir, true));
		ChannelDescription c50 = solvis.getChannelDescription(BurnerLock.CHANNEL_ID);
		Collection<? extends IMode<?>> modes = c50.getModes();
		assertEquals(2, modes.size());

		MyImage auto = new MyImage(ImageIO.read(new File(RESOURCES, "o1-modulation-auto.bmp")));
		MyImage hand = new MyImage(ImageIO.read(new File(RESOURCES, "o1-modulation-hand.bmp")));
		assertEquals(240, auto.getWidth());
		assertEquals(128, auto.getHeight());

		for (IMode<?> mode : modes) {
			ModeEntry entry = (ModeEntry) mode;
			boolean matchesAuto = entry.isMatchingWhite(auto, solvis);
			boolean matchesHand = entry.isMatchingWhite(hand, solvis);
			if (BurnerLock.RELEASE_MODE.equals(entry.getName())) {
				assertTrue(matchesAuto, "Auto must match the Auto screenshot");
				assertFalse(matchesHand, "Auto must not match the Hand screenshot");
			} else {
				assertEquals(BurnerLock.LOCK_MODE, entry.getName());
				assertTrue(matchesHand, "Hand must match the Hand screenshot");
				assertFalse(matchesAuto, "Hand must not match the Auto screenshot");
			}
		}
	}
}
