/*
 * Copyright (C) 2019 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. Declaración mínima, solo para compilar.
 */
package android.net;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IIntResultListener extends IInterface {
    abstract class Stub extends Binder implements IIntResultListener {
        public Stub() {
            throw new RuntimeException("stub!");
        }

        @Override
        public IBinder asBinder() {
            throw new RuntimeException("stub!");
        }
    }

    void onResult(int resultCode);
}
