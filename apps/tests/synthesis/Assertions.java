public class Assertions {
  // field name for the contents of an array
  static String ARRAY_CONTENTS = "contents";

  // no way to check this at runtime; empty body
  static void Unmodifiable(Object base, String field) {}

  static void Assert(boolean bool) {
      if (!bool) {
	  System.out.println("Failed assertion!");
	  Thread.dumpStack();
	  System.exit(1);
      }
  }
}