package moe.shizuku.manager.receiver

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings

/** Schedules, but never performs, privileged startup inside the boot broadcast window. */
class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }

        val scheduler = context.getSystemService(JobScheduler::class.java)
        ShizukuSettings.initialize(context)
        val builder = JobInfo.Builder(
            BootStarterJobService.JOB_ID,
            ComponentName(context, BootStarterJobService::class.java)
        )
            .setMinimumLatency(3_000)
            .setOverrideDeadline(60_000)
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB) {
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        }
        val job = builder.build()
        val result = scheduler.schedule(job)
        Log.i(AppConstants.TAG, "Scheduled BoomInstaller boot start: action=${intent.action} result=$result")
    }
}
