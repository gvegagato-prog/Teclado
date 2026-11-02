/*
 * Copyright (C) 2008 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. Declaración mínima, solo para compilar.
 */
package android.net.wifi;

import android.os.IBinder;
import android.os.IInterface;

public interface IWifiManager extends IInterface {
    abstract class Stub {
        public static IWifiManager asInterface(IBinder obj) {
            throw new RuntimeException("stub!");
        }
    }

    SoftApConfiguration getSoftApConfiguration();

    int getWifiApEnabledState();
}
