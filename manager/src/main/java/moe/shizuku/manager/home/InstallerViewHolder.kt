package moe.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeInstallerBinding
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.installer.InstallerActivity
import moe.shizuku.manager.installer.InstallerIdentity
import moe.shizuku.manager.model.ServiceStatus
import rikka.recyclerview.BaseViewHolder

class InstallerViewHolder(private val binding: HomeInstallerBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeInstallerBinding.inflate(inflater, outer.root, true)
            InstallerViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener(this)
    }

    override fun onBind() {
        val ready = data.isRunning && InstallerIdentity.canHostInstaller(data.uid)
        // The installer owns the root-service bootstrap transaction, so it must remain reachable
        // when Magisk/SU is granted but the BoomInstaller Binder is not running yet.
        itemView.isEnabled = true
        binding.text2.text = itemView.context.getString(
            if (ready) R.string.home_installer_description
            else R.string.home_installer_requires_system
        )
    }

    override fun onClick(view: View) {
        view.context.startActivity(Intent(view.context, InstallerActivity::class.java))
    }
}
