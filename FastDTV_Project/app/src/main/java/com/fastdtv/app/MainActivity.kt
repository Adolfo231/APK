package com.fastdtv.app

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.fastdtv.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val allChannels = mutableListOf<ChannelItem>()
    private val displayedChannels = mutableListOf<ChannelItem>()
    private lateinit var adapter: ChannelAdapter
    private var currentIndex = 0
    private var primaryTunerInputId: String? = null

    private lateinit var prefs: SharedPreferences
    private val PREF_LAST_CHANNEL_ID = "last_channel_id"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTuneRunnable: Runnable? = null
    private val ZAP_DEBOUNCE_MS = 500L

    private val hideBannerRunnable = Runnable {
        binding.channelBanner.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("fastdtv_prefs", Context.MODE_PRIVATE)

        detectPrimaryTunerInput()
        setupTvView()
        setupChannelList()
        setupSearchAndScan()
        reloadChannels()
    }

    override fun onResume() {
        super.onResume()
        detectPrimaryTunerInput()
        reloadChannels()
    }

    private fun detectPrimaryTunerInput() {
        try {
            val tvInputManager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            val inputs = tvInputManager?.tvInputList ?: emptyList()

            for (info in inputs) {
                if (info.type == TvInputInfo.TYPE_TUNER) {
                    primaryTunerInputId = info.id
                    return
                }
            }

            for (info in inputs) {
                if (!info.isPassthroughInput) {
                    primaryTunerInputId = info.id
                    return
                }
            }

            if (inputs.isNotEmpty()) {
                primaryTunerInputId = inputs.first().id
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupTvView() {
        binding.tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) {
                binding.channelBanner.visibility = View.VISIBLE
                handler.removeCallbacks(hideBannerRunnable)
                handler.postDelayed(hideBannerRunnable, 3500)
            }

            override fun onChannelRetuned(inputId: String, channelUri: Uri) {
                showBanner()
            }
        })
    }

    private fun setupSearchAndScan() {
        binding.btnEmptyScan.setOnClickListener {
            launchChannelScan()
        }

        binding.btnEmptyRestore.setOnClickListener {
            registerDefaultDtvChannels()
        }

        binding.btnGuideScan.setOnClickListener {
            launchChannelScan()
        }

        binding.btnGuideRestore.setOnClickListener {
            registerDefaultDtvChannels()
        }

        binding.edtSearchChannel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterChannels(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupChannelList() {
        adapter = ChannelAdapter(displayedChannels) { selectedChannel ->
            binding.quickGuide.visibility = View.GONE
            val idx = allChannels.indexOfFirst { it.id == selectedChannel.id }
            if (idx != -1) {
                tuneChannelImmediate(idx)
            }
        }
        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = adapter
    }

    private fun reloadChannels() {
        allChannels.clear()
        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INPUT_ID,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_SERVICE_NAME,
            TvContract.Channels.COLUMN_DESCRIPTION
        )

        try {
            contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                projection,
                null,
                null,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(TvContract.Channels._ID))
                    var inputId = cursor.getString(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INPUT_ID)) ?: ""
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NUMBER)) ?: ""

                    var name = cursor.getString(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NAME))
                    if (name.isNullOrBlank()) {
                        name = cursor.getString(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_SERVICE_NAME))
                    }
                    if (name.isNullOrBlank()) {
                        name = cursor.getString(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DESCRIPTION))
                    }
                    if (name.isNullOrBlank()) {
                        name = if (number.isNotBlank()) "Canal $number" else "Canal Digital"
                    }

                    if (inputId.isBlank() && primaryTunerInputId != null) {
                        inputId = primaryTunerInputId!!
                    }

                    val uri = TvContract.buildChannelUri(id)
                    allChannels.add(ChannelItem(id, inputId, number, name, uri))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        displayedChannels.clear()
        displayedChannels.addAll(allChannels)
        adapter.updateList(displayedChannels)

        if (allChannels.isEmpty()) {
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.btnEmptyScan.requestFocus()
        } else {
            binding.emptyStateContainer.visibility = View.GONE

            val savedId = prefs.getLong(PREF_LAST_CHANNEL_ID, -1L)
            val savedIndex = allChannels.indexOfFirst { it.id == savedId }
            currentIndex = if (savedIndex != -1) savedIndex else 0

            tuneChannelImmediate(currentIndex)
        }
    }

    private fun filterChannels(query: String) {
        val trimmed = query.trim().lowercase()
        displayedChannels.clear()
        if (trimmed.isEmpty()) {
            displayedChannels.addAll(allChannels)
        } else {
            displayedChannels.addAll(
                allChannels.filter {
                    it.displayName.lowercase().contains(trimmed) ||
                    it.displayNumber.lowercase().contains(trimmed)
                }
            )
        }
        adapter.updateList(displayedChannels)
    }

    fun launchChannelScan() {
        val tvInputManager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val inputs = tvInputManager?.tvInputList ?: emptyList()

        for (info in inputs) {
            if (info.type == TvInputInfo.TYPE_TUNER) {
                val setupIntent = info.createSetupIntent()?.apply {
                    putExtra(TvInputInfo.EXTRA_INPUT_ID, info.id)
                }
                if (setupIntent != null && canResolve(setupIntent)) {
                    startActivity(setupIntent)
                    return
                }

                val settingsIntent = info.createSettingsIntent()?.apply {
                    putExtra(TvInputInfo.EXTRA_INPUT_ID, info.id)
                }
                if (settingsIntent != null && canResolve(settingsIntent)) {
                    startActivity(settingsIntent)
                    return
                }
            }
        }

        val systemIntents = listOf(
            Intent("android.media.tv.action.CHANNEL_SETTINGS"),
            Intent("com.android.tv.settings.CHANNEL_SETUP"),
            Intent("android.settings.TV_INPUT_SETTINGS"),
            Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI),
            Intent().setComponent(ComponentName("com.droidlogic.tvinput", "com.droidlogic.tvinput.settings.ChannelSearchActivity")),
            Intent().setComponent(ComponentName("com.mediatek.tvinput", "com.mediatek.tvinput.channel.ChannelScanActivity")),
            Intent().setComponent(ComponentName("com.tcl.channelscan", "com.tcl.channelscan.MainActivity")),
            Intent().setComponent(ComponentName("org.droidtv.channels", "org.droidtv.channels.ScanActivity"))
        )

        for (intent in systemIntents) {
            if (canResolve(intent)) {
                try {
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    // continue fallback
                }
            }
        }

        registerDefaultDtvChannels()
        Toast.makeText(
            this,
            "Canais DTV registrados! Para nova varredura física, use Configurações da TV > Canais > Antena.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun canResolve(intent: Intent): Boolean {
        return packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    private fun registerDefaultDtvChannels() {
        val tunerId = primaryTunerInputId ?: "com.android.tv/.TvInputService"

        val defaultChannels = listOf(
            Pair("2.1", "TV Cultura HD"),
            Pair("4.1", "SBT HD"),
            Pair("5.1", "Globo HD"),
            Pair("7.1", "Record HD"),
            Pair("9.1", "RedeTV! HD"),
            Pair("11.1", "TV Gazeta HD"),
            Pair("13.1", "Band HD"),
            Pair("14.1", "Record News HD"),
            Pair("21.1", "Canal 21"),
            Pair("32.1", "Ideal TV"),
            Pair("34.1", "Rede Vida HD"),
            Pair("40.1", "TV Aparecida HD"),
            Pair("44.1", "RIT TV")
        )

        for ((num, name) in defaultChannels) {
            val exists = allChannels.any { it.displayNumber == num }
            if (!exists) {
                val values = ContentValues().apply {
                    put(TvContract.Channels.COLUMN_INPUT_ID, tunerId)
                    put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, num)
                    put(TvContract.Channels.COLUMN_DISPLAY_NAME, name)
                    put(TvContract.Channels.COLUMN_SERVICE_NAME, name)
                    put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                    put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    put(TvContract.Channels.COLUMN_BROWSABLE, 1)
                    put(TvContract.Channels.COLUMN_SEARCHABLE, 1)
                }
                try {
                    contentResolver.insert(TvContract.Channels.CONTENT_URI, values)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        Toast.makeText(this, "Lista de canais digitais DTV atualizada com sucesso!", Toast.LENGTH_SHORT).show()
        reloadChannels()
    }

    private fun scheduleTune(newIndex: Int) {
        if (allChannels.isEmpty()) return
        currentIndex = (newIndex + allChannels.size) % allChannels.size
        showBanner()

        pendingTuneRunnable?.let { handler.removeCallbacks(it) }
        pendingTuneRunnable = Runnable {
            val item = allChannels[currentIndex]
            prefs.edit().putLong(PREF_LAST_CHANNEL_ID, item.id).apply()
            if (item.inputId.isNotBlank()) {
                binding.tvView.tune(item.inputId, item.uri)
            }
        }
        handler.postDelayed(pendingTuneRunnable!!, ZAP_DEBOUNCE_MS)
    }

    private fun tuneChannelImmediate(index: Int) {
        if (allChannels.isEmpty() || index !in allChannels.indices) return
        currentIndex = index
        pendingTuneRunnable?.let { handler.removeCallbacks(it) }
        val item = allChannels[currentIndex]
        prefs.edit().putLong(PREF_LAST_CHANNEL_ID, item.id).apply()
        if (item.inputId.isNotBlank()) {
            binding.tvView.tune(item.inputId, item.uri)
        }
        showBanner()
    }

    private fun showBanner() {
        if (allChannels.isEmpty() || currentIndex !in allChannels.indices) return
        val ch = allChannels[currentIndex]
        binding.txtBannerNumber.text = ch.displayNumber
        binding.txtBannerName.text = ch.displayName
        binding.txtBannerProg.text = "Sinal Digital • Ao Vivo (Antena)"
        binding.channelBanner.visibility = View.VISIBLE

        handler.removeCallbacks(hideBannerRunnable)
        handler.postDelayed(hideBannerRunnable, 3500)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.quickGuide.visibility == View.GONE && binding.emptyStateContainer.visibility == View.GONE) {
                    scheduleTune(currentIndex + 1)
                    return true
                }
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.quickGuide.visibility == View.GONE && binding.emptyStateContainer.visibility == View.GONE) {
                    scheduleTune(currentIndex - 1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.quickGuide.visibility == View.GONE && binding.emptyStateContainer.visibility == View.GONE) {
                    binding.quickGuide.visibility = View.VISIBLE
                    binding.recyclerChannels.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.quickGuide.visibility == View.VISIBLE) {
                    binding.quickGuide.visibility = View.GONE
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        binding.tvView.reset()
    }
}
