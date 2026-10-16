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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerDialog(
    context: Context,
    private val onAppSelected: (InstalledApp) -> Unit
) : Dialog(context) {
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvLoading: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnRefresh: Button
    private var scope: CoroutineScope? = null
    private var loadJob: Job? = null
    private val adapter = AppsAdapter(emptyList()) { app ->
        onAppSelected(app)
        dismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_app_picker)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        recyclerView = findViewById(R.id.rv_apps)
        tvLoading = findViewById(R.id.tv_loading)
        etSearch = findViewById(R.id.et_search)
        btnRefresh = findViewById(R.id.btn_refresh_apps)

        recyclerView.layoutManager = LinearLayoutManager(context)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString() ?: "")
            }
        })

        btnRefresh.setOnClickListener {
            BankAppsManager.invalidateCache()
            etSearch.text.clear()
            loadApps()
        }
    }

    override fun onStart() {
        super.onStart()
        scope = MainScope()
        recyclerView.adapter = adapter
        loadApps()
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        loadJob = null
        recyclerView.adapter = null
        super.onStop()
    }

    private fun loadApps() {
        val currentScope = scope ?: return
        loadJob?.cancel()
        recyclerView.visibility = View.GONE
        etSearch.visibility = View.GONE
        btnRefresh.visibility = View.GONE
        tvLoading.text = "Загрузка..."
        tvLoading.visibility = View.VISIBLE
        val appContext = context.applicationContext
        loadJob = currentScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    BankAppsManager.getAllInstalledApps(appContext)
                        .filter { !BankAppsManager.isAppTracked(appContext, it.packageName) }
                }
                adapter.submitApps(apps, etSearch.text.toString())
                tvLoading.visibility = View.GONE
                etSearch.visibility = View.VISIBLE
                btnRefresh.visibility = View.VISIBLE
                recyclerView.visibility = View.VISIBLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                tvLoading.text = "Не удалось загрузить приложения. Повторите попытку."
                btnRefresh.visibility = View.VISIBLE
            }
        }
    }

    private class AppsAdapter(
        private var allApps: List<InstalledApp>,
        private val onItemClick: (InstalledApp) -> Unit
    ) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        private var filteredApps: List<InstalledApp> = allApps

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_app_icon)
            val name: TextView = view.findViewById(R.id.tv_app_name)
            val packageName: TextView = view.findViewById(R.id.tv_package_name)
        }

        fun submitApps(apps: List<InstalledApp>, query: String) {
            allApps = apps
            filter(query)
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
