package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import java.util.List;

public interface IActivityManager extends IInterface {
    void forceStopPackage(String packageName, int userId) throws RemoteException;

    void registerUidObserver(IUidObserver observer, int which, int cutpoint, String callingPackage) throws RemoteException;

    void unregisterUidObserver(IUidObserver observer) throws RemoteException;

    int getUidProcessState(int uid, String callingPackage) throws RemoteException;

    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() throws RemoteException;

    abstract class Stub extends Binder implements IActivityManager {
        public static IActivityManager asInterface(IBinder binder) {
            throw new RuntimeException("Stub!");
        }
    }
}
