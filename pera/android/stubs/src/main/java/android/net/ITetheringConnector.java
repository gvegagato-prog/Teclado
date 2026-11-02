/*
 * Copyright (C) 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. Declaración mínima, solo para compilar.
 * Firmas de packages/modules/Connectivity/Tethering/common/TetheringLib/src/android/net/ITetheringConnector.aidl
 * (Android 12 y posteriores).
 */
package android.net;

import android.os.IBinder;
import android.os.IInterface;

public interface ITetheringConnector extends IInterface {
    abstract class Stub {
        public static ITetheringConnector asInterface(IBinder obj) {
            throw new RuntimeException("stub!");
        }
    }

    void startTethering(
            TetheringRequestParcel request,
            String callerPkg,
            String callingAttributionTag,
            IIntResultListener receiver);

    void stopTethering(
            int type, String callerPkg, String callingAttributionTag, IIntResultListener receiver);
}
