package com.fastdtv.app

import android.content.Context
import android.content.Intent
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

        setupTvView()
        setupChannelList()
        setupSearchAndScan()
        reloadChannels()
    }

    override fun onResume() {
        super.onResume()
        reloadChannels()
    }

    private fun setupTvView() {
        binding.tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) {}

            override fun onChannelRetuned(inputId: String, channelUri: Uri) {
                showBanner()
            }
        })
    }

    private fun setupSearchAndScan() {
        binding.btnEmptyScan.setOnClickListener {
            launchChannelScan()
        }

        binding.btnGuideScan.setOnClickListener {
            launchChannelScan()
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
            TvContract.Channels.COLUMN_DISPLAY_NAME
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
                    val inputId = cursor.getString(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INPUT_ID)) ?: ""
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NUMBER)) ?: ""
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NAME)) ?: "Canal"
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
            if (currentIndex !in allChannels.indices) {
                currentIndex = 0
            }
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
        try {
            val tvInputManager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            val inputs = tvInputManager?.tvInputList ?: emptyList()

            for (info in inputs) {
                if (info.type == TvInputInfo.TYPE_TUNER) {
                    val intent = info.createSetupIntent()
                    if (intent != null) {
                        startActivity(intent)
                        return
                    }
                }
            }

            // Fallback para configurações de canais nativas do Android TV
            val fallbackIntent = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
            startActivity(fallbackIntent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Abra Configurações da TV > Canais > Sintonia Automática de Antena",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun scheduleTune(newIndex: Int) {
        if (allChannels.isEmpty()) return
        currentIndex = (newIndex + allChannels.size) % allChannels.size
        showBanner()

        pendingTuneRunnable?.let { handler.removeCallbacks(it) }
        pendingTuneRunnable = Runnable {
            val item = allChannels[currentIndex]
            binding.tvView.tune(item.inputId, item.uri)
        }
        handler.postDelayed(pendingTuneRunnable!!, ZAP_DEBOUNCE_MS)
    }

    private fun tuneChannelImmediate(index: Int) {
        if (allChannels.isEmpty() || index !in allChannels.indices) return
        currentIndex = index
        pendingTuneRunnable?.let { handler.removeCallbacks(it) }
        val item = allChannels[currentIndex]
        binding.tvView.tune(item.inputId, item.uri)
        showBanner()
    }

    private fun showBanner() {
        if (allChannels.isEmpty()) return
        val ch = allChannels[currentIndex]
        binding.txtBannerNumber.text = ch.displayNumber
        binding.txtBannerName.text = ch.displayName
        binding.txtBannerProg.text = "Sinal Digital • Ao Vivo"
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
