package androidx.test.platform.ui;
public interface UiController {
    boolean injectMotionEvent(android.view.MotionEvent event) throws InjectEventSecurityException;
    boolean injectKeyEvent(android.view.KeyEvent event) throws InjectEventSecurityException;
    boolean injectString(String str) throws InjectEventSecurityException;
    void loopMainThreadUntilIdle();
    void loopMainThreadForAtLeast(long millisDelay);
}
