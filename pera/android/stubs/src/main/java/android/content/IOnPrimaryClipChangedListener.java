/*
 * Copyright (C) 2008 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. Declaración mínima, solo para compilar.
 */
package android.content;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IOnPrimaryClipChangedListener extends IInterface {
    abstract class Stub extends Binder implements IOnPrimaryClipChangedListener {
        public Stub() {
            throw new RuntimeException("stub!");
        }

        @Override
        public IBinder asBinder() {
            throw new RuntimeException("stub!");
        }
    }

    void dispatchPrimaryClipChanged();
}
