package android.os;

public interface IDeviceIdleController extends IInterface {
    void addPowerSaveWhitelistApp(String name) throws RemoteException;

    boolean isPowerSaveWhitelistApp(String name) throws RemoteException;

    abstract class Stub extends Binder implements IDeviceIdleController {
        public static IDeviceIdleController asInterface(IBinder binder) {
            throw new RuntimeException("Stub!");
        }
    }
}
