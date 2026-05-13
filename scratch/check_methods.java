
import java.lang.reflect.Method;
import net.minecraft.server.world.ServerWorld;

public class MethodChecker {
    public static void check() {
        try {
            for (Method m : ServerWorld.class.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("chunk") && m.getName().toLowerCase().contains("force")) {
                    System.out.println("Found method: " + m.getName() + " params: " + java.util.Arrays.toString(m.getParameterTypes()));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
MethodChecker.check();
