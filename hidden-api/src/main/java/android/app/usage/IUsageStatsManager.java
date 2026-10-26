package android.app.usage;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IUsageStatsManager extends IInterface {
    void setAppStandbyBucket(String packageName, int bucket, int userId) throws RemoteException;

    int getAppStandbyBucket(String packageName, String callingPackage, int userId) throws RemoteException;

    abstract class Stub extends Binder implements IUsageStatsManager {
        public static IUsageStatsManager asInterface(IBinder binder) {
            throw new RuntimeException("Stub!");
        }
    }
}
