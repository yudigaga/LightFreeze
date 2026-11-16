package io.github.yudigaga.lightfreeze.control;

import android.content.pm.PackageManager;
import android.os.IBinder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

final class ShizukuBackend implements FreezeBackend {
    private IBinder preparedServer, preparedPackage;
    private Object preparedPm;
    private Method preparedSet;
    private final InitialBinderWait initialBinder = new InitialBinderWait();
    @Override public String name() { return "Shizuku"; }
    @Override public void check() throws BackendException {
        try {
            initialBinder.await(new InitialBinderWait.Signal() {
                private Shizuku.OnBinderReceivedListener listener;
                public boolean available() { return Shizuku.pingBinder(); }
                public void add(Runnable callback) {
                    listener = callback::run; Shizuku.addBinderReceivedListenerSticky(listener);
                }
                public void remove(Runnable callback) { if (listener != null) Shizuku.removeBinderReceivedListener(listener); }
            }, 2000);
            if (!Shizuku.pingBinder()) throw new BackendException("Shizuku 未运行或连接已断开", false);
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                throw new BackendException("尚未获得 Shizuku 授权，请在设置中授权", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new BackendException("Shizuku 连接等待已中断，未写入", false, e);
        } catch (RuntimeException e) { throw new BackendException("Shizuku 连接不可用", false, e); }
    }
    @Override public void write(String packageName, int userId, int setting) throws BackendException {
        write(packageName, userId, setting, () -> { });
    }
    @Override public void write(String packageName, int userId, int setting, WriteGate gate) throws BackendException {
        ControlRules.command(packageName, userId, setting);
        check();
        boolean invoked = false;
        try {
            IBinder server = Shizuku.getBinder();
            if (preparedServer != server || preparedPackage == null || !preparedPackage.isBinderAlive()) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;", "Landroid/content/pm/IPackageManager");
                IBinder packageBinder = SystemServiceHelper.getSystemService("package");
                if (packageBinder == null) throw new BackendException("系统 PackageManager Binder 不可用", false);
                Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
                preparedPm = stub.getMethod("asInterface", IBinder.class).invoke(null, new ShizukuBinderWrapper(packageBinder));
                Class<?> api = Class.forName("android.content.pm.IPackageManager");
                preparedSet = api.getMethod("setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class);
                preparedServer = server; preparedPackage = packageBinder;
            }
            String callingPackage = Shizuku.getUid() == 0 ? null : "com.android.shell";
            gate.check();
            invoked = true;
            preparedSet.invoke(preparedPm, packageName, setting, 0, userId, callingPackage);
            if (server != Shizuku.getBinder() || !server.isBinderAlive())
                throw new BackendException("Shizuku 在操作中断开，结果待核对", true);
            check();
        } catch (BackendException e) { throw e; }
        catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            boolean unknown = invoked && !(cause instanceof SecurityException) && !(cause instanceof IllegalArgumentException);
            throw new BackendException("Shizuku 写入失败：" + cause.getClass().getSimpleName() + "，未切换权限源", unknown, cause);
        }
    }
}
