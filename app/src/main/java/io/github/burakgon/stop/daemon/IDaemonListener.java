package io.github.burakgon.stop.daemon;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written replacement for the AIDL-generated {@code oneway interface IDaemonListener}.
 *
 * <p>Oneway semantics matter here: the caller must not block waiting for the app process, so the
 * Proxy uses {@link IBinder#FLAG_ONEWAY} and never reads a reply, and {@code onTransact} never
 * writes a no-exception header.
 */
public interface IDaemonListener extends IInterface {

    String DESCRIPTOR = "io.github.burakgon.stop.daemon.IDaemonListener";

    int TRANSACTION_onEvents = 1;
    int TRANSACTION_onPackagesChanged = 2;

    void onEvents(String events) throws RemoteException;

    void onPackagesChanged(String[] packages) throws RemoteException;

    abstract class Stub extends android.os.Binder implements IDaemonListener {

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static IDaemonListener asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDaemonListener) {
                return (IDaemonListener) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case 0x5f4e5446: { // INTERFACE_TRANSACTION
                    if (reply != null) {
                        reply.writeString(DESCRIPTOR);
                    }
                    return true;
                }
                case TRANSACTION_onEvents: {
                    data.enforceInterface(DESCRIPTOR);
                    String arg0 = data.readString();
                    this.onEvents(arg0);
                    return true;
                }
                case TRANSACTION_onPackagesChanged: {
                    data.enforceInterface(DESCRIPTOR);
                    String[] arg0 = data.createStringArray();
                    this.onPackagesChanged(arg0);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IDaemonListener {

            private final IBinder mRemote;

            Proxy(IBinder remote) {
                this.mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void onEvents(String events) throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(events);
                    mRemote.transact(TRANSACTION_onEvents, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }

            @Override
            public void onPackagesChanged(String[] packages) throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStringArray(packages);
                    mRemote.transact(TRANSACTION_onPackagesChanged, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }
        }
    }
}
