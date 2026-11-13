// Regression probe for a delayed macOS selectedRange callback with no active client.
// This models a lifecycle boundary; it is not a reproduction of a user crash by itself.
// Run only in a separate JVM, with --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED.
import java.awt.Toolkit;
import java.lang.reflect.*;
import java.util.Arrays;
public class InactiveInputMethodProbe {
  public static void main(String[] args) throws Exception {
    System.out.println(System.getProperty("java.runtime.version")+" / "+System.getProperty("java.vendor"));
    Toolkit.getDefaultToolkit();
    Class<?> c = Class.forName("sun.lwawt.macosx.CInputMethod");
    Constructor<?> ctor = c.getDeclaredConstructor(); ctor.setAccessible(true);
    Object im = ctor.newInstance();
    Method selected = c.getDeclaredMethod("selectedRange"); selected.setAccessible(true);
    try {
      int[] range = (int[]) selected.invoke(im);
      if (range == null || range.length != 2 || range[0] != 0 || range[1] != 0)
        throw new AssertionError("Expected empty inactive selection: " + Arrays.toString(range));
      System.out.println("PASS inactive selectedRange=" + Arrays.toString(range));
    }
    catch (InvocationTargetException e) {e.getCause().printStackTrace(); System.exit(2);}
    System.exit(0);
  }
}
