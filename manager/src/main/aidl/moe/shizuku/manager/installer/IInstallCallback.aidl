package moe.shizuku.manager.installer;

oneway interface IInstallCallback {
    void onStatus(String message);
    void onProgress(long writtenBytes, long totalBytes);
    void onFinished(int status, String message, String packageName);
}
