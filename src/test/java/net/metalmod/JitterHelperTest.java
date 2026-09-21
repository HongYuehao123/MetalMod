package net.metalmod;

import net.metalmod.render.JitterHelper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JitterHelperTest {

    @Test
    public void testHaltonJitterRange() {
        int renderWidth = 1920;
        int renderHeight = 1080;

        for (int i = 0; i < 32; i++) {
            JitterHelper.advance(renderWidth, renderHeight);

            float jx = JitterHelper.getJitterX();
            float jy = JitterHelper.getJitterY();

            // Pixel jitter must be strictly in [-0.5, 0.5]
            assertTrue(jx >= -0.5f && jx <= 0.5f, "Jitter X out of range: " + jx);
            assertTrue(jy >= -0.5f && jy <= 0.5f, "Jitter Y out of range: " + jy);

            // Projection matrix offsets
            float projX = JitterHelper.getProjectionJitterX(renderWidth);
            float projY = JitterHelper.getProjectionJitterY(renderHeight);

            assertEquals((2.0f * jx) / renderWidth, projX, 1e-6f);
            assertEquals((2.0f * jy) / renderHeight, projY, 1e-6f);
        }
    }
}
