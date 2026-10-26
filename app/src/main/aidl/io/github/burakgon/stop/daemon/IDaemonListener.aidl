package io.github.burakgon.stop.daemon;

/** Implemented by the app while its process is alive. */
oneway interface IDaemonListener {
    /** JSON array of DaemonEvent. */
    void onEvents(String events) = 1;

    /** Packages that were installed, updated or removed. */
    void onPackagesChanged(in String[] packages) = 2;
}
