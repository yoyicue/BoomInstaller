package moe.shizuku.manager.installer;

import android.content.Intent;

oneway interface IInstallCallback {
    void onProgress(long writtenBytes, long totalBytes);
    void onUserActionRequired(in Intent intent);
    void onFinished(int status, String message, String packageName);
}
