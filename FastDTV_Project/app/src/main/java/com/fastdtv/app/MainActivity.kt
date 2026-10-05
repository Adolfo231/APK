package com.fastdtv.app

import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvView
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.fastdtv.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val channels = mutableListOf<ChannelItem>()
    private var currentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTuneRunnable: Runnable? = null
    private val ZAP_DEBOUNCE_MS = 500L // 500ms debounce para troca rápida sem engasgar o tuner

    private val hideBannerRunnable = Runnable {
        binding.channelBanner.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTvView()
        loadPhysicalChannels()
        setupChannelList()

        if (channels.isNotEmpty()) {
            tuneChannelImmediate(0)
        } else {
            Toast.makeText(this, R.string.no_channels, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupTvView() {
        binding.tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) {
                // Imagem sincronizada e ativa
            }

            override fun onChannelRetuned(inputId: String, channelUri: Uri) {
                showBanner()
            }
        })
    }

    private fun loadPhysicalChannels() {
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

                    channels.add(ChannelItem(id, inputId, number, name, uri))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupChannelList() {
        val adapter = ChannelAdapter(channels) { selectedIndex ->
            binding.quickGuide.visibility = View.GONE
            tuneChannelImmediate(selectedIndex)
        }
        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = adapter
    }

    private fun scheduleTune(newIndex: Int) {
        if (channels.isEmpty()) return
        currentIndex = (newIndex + channels.size) % channels.size
        showBanner()

        pendingTuneRunnable?.let { handler.removeCallbacks(it) }
        pendingTuneRunnable = Runnable {
            val item = channels[currentIndex]
            binding.tvView.tune(item.inputId, item.uri)
        }
        handler.postDelayed(pendingTuneRunnable!!, ZAP_DEBOUNCE_MS)
    }

    private fun tuneChannelImmediate(index: Int) {
        if (channels.isEmpty() || index !in channels.indices) return
        currentIndex = index
        pendingTuneRunnable?.let { handler.removeCallbacks(it) }
        val item = channels[currentIndex]
        binding.tvView.tune(item.inputId, item.uri)
        showBanner()
    }

    private fun showBanner() {
        if (channels.isEmpty()) return
        val ch = channels[currentIndex]
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
                if (binding.quickGuide.visibility == View.GONE) {
                    scheduleTune(currentIndex + 1)
                    return true
                }
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.quickGuide.visibility == View.GONE) {
                    scheduleTune(currentIndex - 1)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.quickGuide.visibility == View.GONE) {
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
