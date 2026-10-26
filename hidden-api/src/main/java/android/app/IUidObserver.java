package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Latest known shape (Android 15+). Older releases differ: onUidStateChanged had no capability
 * argument before Android 11 and onUidProcAdjChanged took a single argument on Android 14.
 * Implementations must also declare those variants so the device's real Stub can dispatch to them.
 */
public interface IUidObserver extends IInterface {
    void onUidGone(int uid, boolean disabled) throws RemoteException;

    void onUidActive(int uid) throws RemoteException;

    void onUidIdle(int uid, boolean disabled) throws RemoteException;

    void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) throws RemoteException;

    void onUidProcAdjChanged(int uid, int adj) throws RemoteException;

    void onUidCachedChanged(int uid, boolean cached) throws RemoteException;

    abstract class Stub extends Binder implements IUidObserver {
        @Override
        public IBinder asBinder() {
            throw new RuntimeException("Stub!");
        }
    }
}
