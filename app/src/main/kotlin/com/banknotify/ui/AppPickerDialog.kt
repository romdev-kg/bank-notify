package com.banknotify.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
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
        val etSearch = findViewById<EditText>(R.id.et_search)
        val btnRefresh = findViewById<Button>(R.id.btn_refresh_apps)

        recyclerView.layoutManager = LinearLayoutManager(context)

        loadApps(recyclerView, tvLoading, etSearch, btnRefresh)

        btnRefresh.setOnClickListener {
            BankAppsManager.invalidateCache()
            etSearch.text.clear()
            recyclerView.visibility = View.GONE
            etSearch.visibility = View.GONE
            btnRefresh.visibility = View.GONE
            tvLoading.visibility = View.VISIBLE
            loadApps(recyclerView, tvLoading, etSearch, btnRefresh)
        }
    }

    private fun loadApps(
        recyclerView: RecyclerView,
        tvLoading: TextView,
        etSearch: EditText,
        btnRefresh: Button
    ) {
        Thread {
            val apps = BankAppsManager.getAllInstalledApps(context)
                .filter { !BankAppsManager.isAppTracked(context, it.packageName) }

            recyclerView.post {
                tvLoading.visibility = View.GONE
                etSearch.visibility = View.VISIBLE
                btnRefresh.visibility = View.VISIBLE
                recyclerView.visibility = View.VISIBLE

                val adapter = AppsAdapter(apps) { app ->
                    onAppSelected(app)
                    dismiss()
                }
                recyclerView.adapter = adapter

                etSearch.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        adapter.filter(s?.toString() ?: "")
                    }
                })
            }
        }.start()
    }

    private class AppsAdapter(
        private val allApps: List<InstalledApp>,
        private val onItemClick: (InstalledApp) -> Unit
    ) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        private var filteredApps: List<InstalledApp> = allApps

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_app_icon)
            val name: TextView = view.findViewById(R.id.tv_app_name)
            val packageName: TextView = view.findViewById(R.id.tv_package_name)
        }

        fun filter(query: String) {
            filteredApps = if (query.isEmpty()) {
                allApps
            } else {
                val q = query.lowercase()
                allApps.filter {
                    it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = filteredApps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.appName
            holder.packageName.text = app.packageName
            holder.itemView.setOnClickListener { onItemClick(app) }
        }

        override fun getItemCount() = filteredApps.size
    }
}
