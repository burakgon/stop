package io.github.burakgon.stop.daemon;

import io.github.burakgon.stop.daemon.IDaemonListener;

/** Privileged daemon that runs as shell (or root) through a Shizuku user service. */
interface IStopDaemon {
    /** Reserved by Shizuku: called when the user service is being removed. */
    void destroy() = 16777114;

    /** JSON, see DaemonInfo. */
    String getInfo() = 1;

    /** Replaces the whole enforcement configuration. JSON, see DaemonConfig. */
    void applyConfig(String config) = 2;

    void setListener(IDaemonListener listener) = 3;

    /** JSON array of DaemonEvent with id greater than afterId, oldest first. */
    String getEvents(long afterId) = 4;

    /** JSON array of RunningApp: blocked apps that currently have processes. */
    String getRunningBlocked() = 5;

    /** Force-stops the given packages right now. Returns how many were running. */
    int stopNow(in String[] packages) = 6;

    /** Force-stops every blocked app that isn't on screen. Returns how many were running. */
    int stopAllBlocked() = 7;

    /** Undoes every background restriction the daemon applied, regardless of settings. */
    void resetRestrictions() = 8;

    /** Freezes or restores one package (desktop shortcut). */
    boolean setFrozen(String packageName, boolean frozen) = 9;

    /** Force-stops one package right now, ignoring rules and grace periods. */
    boolean forceStopNow(String packageName) = 10;

    /** Suspends one package right now, then stops it. */
    boolean suspendNow(String packageName) = 11;

    /** Comma-separated packages the daemon froze (it knows which mechanism was used). */
    String getFrozen() = 12;

    /** Comma-separated subset of the given packages the framework reports as suspended. */
    String getSuspended(String packages) = 13;
}
