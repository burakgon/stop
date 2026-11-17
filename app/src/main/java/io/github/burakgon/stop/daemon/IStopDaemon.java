package io.github.burakgon.stop.daemon;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written replacement for the AIDL-generated interface.
 *
 * <p>The on-device build-tools ship an x86-64 {@code aidl} binary, so AIDL compilation is turned
 * off and this equivalent stub/Proxy pair is compiled instead. Wire format is unchanged: the same
 * transaction codes, the same parcel layout, so a peer built from the original AIDL still talks to
 * this one.
 */
public interface IStopDaemon extends IInterface {

    String DESCRIPTOR = "io.github.burakgon.stop.daemon.IStopDaemon";

    int TRANSACTION_destroy = 16777114;
    int TRANSACTION_getInfo = 1;
    int TRANSACTION_applyConfig = 2;
    int TRANSACTION_setListener = 3;
    int TRANSACTION_getEvents = 4;
    int TRANSACTION_getRunningBlocked = 5;
    int TRANSACTION_stopNow = 6;
    int TRANSACTION_stopAllBlocked = 7;
    int TRANSACTION_resetRestrictions = 8;
    int TRANSACTION_setFrozen = 9;
    int TRANSACTION_forceStopNow = 10;
    int TRANSACTION_suspendNow = 11;
    int TRANSACTION_getFrozen = 12;
    int TRANSACTION_getSuspended = 13;

    void destroy() throws RemoteException;

    String getInfo() throws RemoteException;

    void applyConfig(String config) throws RemoteException;

    void setListener(IDaemonListener listener) throws RemoteException;

    String getEvents(long afterId) throws RemoteException;

    String getRunningBlocked() throws RemoteException;

    int stopNow(String[] packages) throws RemoteException;

    int stopAllBlocked() throws RemoteException;

    void resetRestrictions() throws RemoteException;

    /**
     * Freezes or restores one package right away, used by the desktop shortcut. Unlike the work
     * modes this is an explicit per-app decision, so it survives configuration changes.
     */
    boolean setFrozen(String packageName, boolean frozen) throws RemoteException;

    /** Force-stops one package right away, whatever the rules or work mode say. */
    boolean forceStopNow(String packageName) throws RemoteException;

    /** Suspends one package right away without tracking it as a permanent freeze. */
    boolean suspendNow(String packageName) throws RemoteException;

    /** Comma separated names of the packages permanently frozen by Stop. */
    String getFrozen() throws RemoteException;

    String getSuspended(String packages) throws RemoteException;

    abstract class Stub extends android.os.Binder implements IStopDaemon {

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static IStopDaemon asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IStopDaemon) {
                return (IStopDaemon) iin;
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
                case TRANSACTION_destroy: {
                    data.enforceInterface(DESCRIPTOR);
                    this.destroy();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACTION_getInfo: {
                    data.enforceInterface(DESCRIPTOR);
                    String result = this.getInfo();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(result);
                    }
                    return true;
                }
                case TRANSACTION_applyConfig: {
                    data.enforceInterface(DESCRIPTOR);
                    String arg0 = data.readString();
                    this.applyConfig(arg0);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACTION_setListener: {
                    data.enforceInterface(DESCRIPTOR);
                    IDaemonListener arg0 =
                            IDaemonListener.Stub.asInterface(data.readStrongBinder());
                    this.setListener(arg0);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACTION_getEvents: {
                    data.enforceInterface(DESCRIPTOR);
                    long arg0 = data.readLong();
                    String result = this.getEvents(arg0);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(result);
                    }
                    return true;
                }
                case TRANSACTION_getRunningBlocked: {
                    data.enforceInterface(DESCRIPTOR);
                    String result = this.getRunningBlocked();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(result);
                    }
                    return true;
                }
                case TRANSACTION_getFrozen: {
                    data.enforceInterface(DESCRIPTOR);
                    String result = this.getFrozen();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(result);
                    }
                    return true;
                }
                case TRANSACTION_getSuspended: {
                    data.enforceInterface(DESCRIPTOR);
                    String arg0 = data.readString();
                    String result = this.getSuspended(arg0);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(result);
                    }
                    return true;
                }
                case TRANSACTION_stopNow: {
                    data.enforceInterface(DESCRIPTOR);
                    String[] arg0 = data.createStringArray();
                    int result = this.stopNow(arg0);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result);
                    }
                    return true;
                }
                case TRANSACTION_stopAllBlocked: {
                    data.enforceInterface(DESCRIPTOR);
                    int result = this.stopAllBlocked();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result);
                    }
                    return true;
                }
                case TRANSACTION_resetRestrictions: {
                    data.enforceInterface(DESCRIPTOR);
                    this.resetRestrictions();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case TRANSACTION_setFrozen: {
                    data.enforceInterface(DESCRIPTOR);
                    String packageName = data.readString();
                    boolean frozen = data.readInt() != 0;
                    boolean result = this.setFrozen(packageName, frozen);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result ? 1 : 0);
                    }
                    return true;
                }
                case TRANSACTION_forceStopNow: {
                    data.enforceInterface(DESCRIPTOR);
                    String packageName = data.readString();
                    boolean result = this.forceStopNow(packageName);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result ? 1 : 0);
                    }
                    return true;
                }
                case TRANSACTION_suspendNow: {
                    data.enforceInterface(DESCRIPTOR);
                    String packageName = data.readString();
                    boolean result = this.suspendNow(packageName);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(result ? 1 : 0);
                    }
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IStopDaemon {

            private final IBinder mRemote;

            Proxy(IBinder remote) {
                this.mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void destroy() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_destroy, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String getInfo() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getInfo, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void applyConfig(String config) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(config);
                    mRemote.transact(TRANSACTION_applyConfig, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void setListener(IDaemonListener listener) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(listener == null ? null : listener.asBinder());
                    mRemote.transact(TRANSACTION_setListener, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String getEvents(long afterId) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeLong(afterId);
                    mRemote.transact(TRANSACTION_getEvents, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String getRunningBlocked() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getRunningBlocked, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public int stopNow(String[] packages) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStringArray(packages);
                    mRemote.transact(TRANSACTION_stopNow, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public int stopAllBlocked() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_stopAllBlocked, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void resetRestrictions() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_resetRestrictions, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean setFrozen(String packageName, boolean frozen) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(packageName);
                    data.writeInt(frozen ? 1 : 0);
                    mRemote.transact(TRANSACTION_setFrozen, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

        @Override
        public boolean forceStopNow(String packageName) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(packageName);
                mRemote.transact(TRANSACTION_forceStopNow, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public boolean suspendNow(String packageName) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(packageName);
                mRemote.transact(TRANSACTION_suspendNow, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public String getFrozen() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                mRemote.transact(TRANSACTION_getFrozen, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        public String getSuspended(String packages) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(packages);
                mRemote.transact(TRANSACTION_getSuspended, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        }
    }
}
