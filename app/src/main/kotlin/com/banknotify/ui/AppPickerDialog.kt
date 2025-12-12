package com.banknotify.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.banknotify.BankAppsManager
import com.banknotify.InstalledApp
import com.banknotify.R

class AppPickerDialog(
    context: Context,
    private val onAppSelected: (InstalledApp) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_app_picker)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val recyclerView = findViewById<RecyclerView>(R.id.rv_apps)
        val tvLoading = findViewById<TextView>(R.id.tv_loading)

        recyclerView.layoutManager = LinearLayoutManager(context)

        // Загружаем приложения в фоне
        Thread {
            val apps = BankAppsManager.getAllInstalledApps(context)
                .filter { !BankAppsManager.isAppTracked(context, it.packageName) }

            recyclerView.post {
                tvLoading.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter = AppsAdapter(apps) { app ->
                    onAppSelected(app)
                    dismiss()
                }
            }
        }.start()
    }

    private class AppsAdapter(
        private val apps: List<InstalledApp>,
        private val onItemClick: (InstalledApp) -> Unit
    ) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_app_icon)
            val name: TextView = view.findViewById(R.id.tv_app_name)
            val packageName: TextView = view.findViewById(R.id.tv_package_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.appName
            holder.packageName.text = app.packageName
            holder.itemView.setOnClickListener { onItemClick(app) }
        }

        override fun getItemCount() = apps.size
    }
}
