package androidx.test.platform.app;
public final class InstrumentationRegistry {
    private static volatile android.app.Instrumentation instrumentation;
    private static volatile android.os.Bundle args;
    public static android.app.Instrumentation getInstrumentation() {
        if (instrumentation == null) throw new IllegalStateException("No instrumentation registered");
        return instrumentation;
    }
    public static android.os.Bundle getArguments() { return args == null ? new android.os.Bundle() : args; }
    public static void registerInstance(android.app.Instrumentation i, android.os.Bundle b) { instrumentation = i; args = b; }
}
