package moe.shizuku.manager.installer;

import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;

final class IntentSenderFactory {

    interface Callback {
        void onIntent(Intent intent);
    }

    private static final class Adaptor extends IIntentSender.Stub {

        private final Callback callback;

        private Adaptor(Callback callback) {
            this.callback = callback;
        }

        private void dispatch(Intent intent) {
            callback.onIntent(intent);
        }

        @Override
        public int send(int code, Intent intent, String resolvedType,
                IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
            dispatch(intent);
            return 0;
        }

        @Override
        public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
            dispatch(intent);
        }
    }

    static IntentSender create(Callback callback) throws NoSuchMethodException,
            InvocationTargetException, InstantiationException, IllegalAccessException {
        return IntentSender.class.getConstructor(IIntentSender.class)
                .newInstance(new Adaptor(callback));
    }

    private IntentSenderFactory() {
    }
}
