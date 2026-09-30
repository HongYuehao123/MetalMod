import net.metalmod.metalfx.ProjectionJitter;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** CPU regression: the camera jitter must move every depth by the advertised pixel offset.
 * Reproduces CameraJitterMixin's matrix operation; does not load or transform the mixin.
 * Exercises the production apply/remove helpers. No GPU or game launch required.
 */
class JitterCheck {
    public static void main(String[] args) {
        int width = 1920, height = 1080, failures = 0;
        Matrix4f projection = new Matrix4f().setPerspective(
                (float) Math.toRadians(70), (float) width / height, 0.05f, 1000f, true);
        ProjectionJitter.clear();
        try {
            for (int phase = 0; phase < 16; phase++) {
                ProjectionJitter.beginFrame();
                float x = ProjectionJitter.clipX(width), y = ProjectionJitter.clipY(height);
                Matrix4f actual = new Matrix4f(projection);
                ProjectionJitter.apply(actual, width, height);
                Matrix4f bob = new Matrix4f().rotateZ(0.12f).translate(0.02f, -0.03f, 0);
                Matrix4f unjittered = new Matrix4f(actual).mul(bob);
                ProjectionJitter.remove(unjittered, width, height);
                if (!unjittered.equals(new Matrix4f(projection).mul(bob), 0.00001f))
                    throw new AssertionError("Jitter removal changed bob/portal composition");
                // Independent control: a homogeneous clip translation after projection.
                Matrix4f control = new Matrix4f().translation(x, y, 0).mul(projection);
                for (float depth : new float[]{0.1f, 1f, 10f, 100f}) {
                    Vector4f base = project(projection, depth);
                    Vector4f shifted = project(actual, depth);
                    Vector4f correct = project(control, depth);
                    float dx = (shifted.x - base.x) * width / 2;
                    float dy = (shifted.y - base.y) * height / 2;
                    float expectedX = ProjectionJitter.offsetX(), expectedY = ProjectionJitter.offsetY();
                    if (Math.abs((correct.x - base.x) * width / 2 - expectedX) > 0.0001f
                            || Math.abs((correct.y - base.y) * height / 2 - expectedY) > 0.0001f)
                        throw new AssertionError("Independent clip-space control failed");
                    if (Math.abs(dx - expectedX) > 0.0001f || Math.abs(dy - expectedY) > 0.0001f) {
                        failures++;
                        if (phase == 0) System.out.printf(
                                "FAIL depth=%6.1f expected=(%.6f,%.6f) actual=(%.6f,%.6f) pixels%n",
                                depth, expectedX, expectedY, dx, dy);
                    }
                }
                ProjectionJitter.endFrame();
            }
        } finally {
            ProjectionJitter.clear();
        }
        System.out.println("Clip-space control passed all 64 cases.");
        if (failures != 0) throw new AssertionError(failures + "/64 camera jitter cases failed");
        for (String effect : new String[]{"off", "spatial", "temporal"}) {
            var plan = net.metalmod.metalfx.UpscalingPlan.resolve(2560, 1440, 0.75, effect);
            boolean off = effect.equals("off");
            if (plan.scaled() == off || plan.worldWidth() != (off ? 2560 : 1920)
                    || plan.worldHeight() != (off ? 1440 : 1080)
                    || plan.temporalRequested() != effect.equals("temporal"))
                throw new AssertionError("Incorrect mode plan: " + plan);
        }
        System.out.println("JITTER CHECK PASSED (including mode plans and bob/portal removal)");
    }

    private static Vector4f project(Matrix4f matrix, float depth) {
        Vector4f p = matrix.transform(new Vector4f(0, 0, -depth, 1));
        return p.div(p.w);
    }
}
