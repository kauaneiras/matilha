package androidx.test.runner.lifecycle;
import java.util.Collection;
public interface ActivityLifecycleMonitor {
    void addLifecycleCallback(ActivityLifecycleCallback callback);
    void removeLifecycleCallback(ActivityLifecycleCallback callback);
    Stage getLifecycleStageOf(android.app.Activity activity);
    Collection<android.app.Activity> getActivitiesInStage(Stage stage);
}
