// File: MainActivity.kt
package com.example.rustydlnaclient

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.HashMap // Ensure this is imported for MediaMetadataRetriever
import javax.xml.parsers.DocumentBuilderFactory
import android.content.Context
import android.net.ConnectivityManager
class MainActivity : ComponentActivity() {
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        return activeNetworkInfo != null && activeNetworkInfo.isConnected
    }

    private lateinit var recyclerView: RecyclerView
    private val displayItems = mutableListOf<DisplayItem>()
    private val handler = Handler(Looper.getMainLooper())

    // Add VideoProgressManager
    private lateinit var progressManager: VideoProgressManager

    private var selectedServerUrl: String? = null
    private var selectedDirectory: String? = null
    private var isShowingServers = true
    private var isLoading = false

    // Add flag to track if SSDP discovery is running
    private var isDiscovering = false
    private var discoverySocket: DatagramSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize progress manager
        progressManager = VideoProgressManager(this)

        // Make the activity fullscreen and handle system bars properly
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            // 1. Make background black by default
            setBackgroundColor(0xFF000000.toInt()) // Black background
        }

        // Handle system insets (status bar, navigation bar, etc.)
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            addItemDecoration(DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL))
            adapter = CombinedAdapter()
            itemAnimator = null
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {
                    RecyclerView.SCROLL_STATE_IDLE -> Log.d("SCROLL", "Scroll idle")
                    RecyclerView.SCROLL_STATE_DRAGGING -> Log.d("SCROLL", "User is dragging")
                    RecyclerView.SCROLL_STATE_SETTLING -> Log.d("SCROLL", "RecyclerView is settling")
                }
            }
        })
        layout.addView(recyclerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(layout)

        // Setup modern back navigation
        setupBackNavigation()

        // Initialize the app state
        initializeApp()
    }

    override fun onResume() {
        super.onResume()
        // If we're showing servers but have no items, restart discovery
        if (isShowingServers && displayItems.isEmpty() && !isDiscovering) {
            startSSDP()
        }
        // Refresh only video items when returning from video player
        if (!isShowingServers) {
            // PERFORMANCE FIX: Only update video items, not everything
            displayItems.forEachIndexed { index, item ->
                if (item is DisplayItem.Video) {
                    recyclerView.adapter?.notifyItemChanged(index)
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isShowingServers) {
                    navigateBack()
                } else {
                    // If we're showing servers and user presses back, exit the app
                    finish()
                }
            }
        })
    }

    private fun initializeApp() {
        // Reset state to initial values
        selectedServerUrl = null
        selectedDirectory = null
        isShowingServers = true
        isLoading = false

        // Clear existing items and start discovery
        displayItems.clear()
        recyclerView.adapter?.notifyDataSetChanged()

        startSSDP()
    }

    override fun onPause() {
        super.onPause()
        // Stop discovery when paused to avoid resource leaks
        stopSSDP()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSSDP()
    }
    private fun showNoNetworkState() {
        val oldSize = displayItems.size
        displayItems.clear()

        displayItems.add(DisplayItem.Header)
        displayItems.add(DisplayItem.NoNetwork)

        if (oldSize > 0) {
            recyclerView.adapter?.notifyItemRangeRemoved(0, oldSize)
        }
        recyclerView.adapter?.notifyItemRangeInserted(0, displayItems.size)
    }
    private fun startSSDP() {
        if (isDiscovering) return

        // Check network connectivity before starting discovery
        if (!isNetworkAvailable()) {
            handler.post {
                showNoNetworkState()
            }
            return
        }

        isDiscovering = true
        Thread { discoverSSDP() }.start()
    }

    private fun stopSSDP() {
        isDiscovering = false
        discoverySocket?.close()
        discoverySocket = null
    }

    // Add method to show loading state
    private fun showLoadingState() {
        handler.post {
            isLoading = true
            val oldSize = displayItems.size
            displayItems.clear()
            displayItems.add(DisplayItem.Loading)

            // PERFORMANCE FIX: Use range operations instead of notifyDataSetChanged
            if (oldSize > 0) {
                recyclerView.adapter?.notifyItemRangeRemoved(0, oldSize)
            }
            recyclerView.adapter?.notifyItemInserted(0)
        }
    }

    // Add method to hide loading state
    private fun hideLoadingState() {
        handler.post {
            isLoading = false
            val loadingIndices = displayItems.withIndex()
                .filter { it.value is DisplayItem.Loading }
                .map { it.index }
                .sortedDescending() // Remove from end to start to avoid index shifting

            for (index in loadingIndices) {
                displayItems.removeAt(index)
                recyclerView.adapter?.notifyItemRemoved(index)
            }
        }
    }

    private fun discoverSSDP() {
        try {
            discoverySocket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 5000
            }

            val request = """
            M-SEARCH * HTTP/1.1
            HOST:239.255.255.250:1900
            MAN:"ssdp:discover"
            MX:3
            ST:urn:schemas-upnp-org:device:MediaServer:1

        """.trimIndent()

            discoverySocket?.send(DatagramPacket(request.toByteArray(), request.length, InetAddress.getByName("239.255.255.250"), 1900))

            val buf = ByteArray(2048)
            var foundServer = false
            val timeout = System.currentTimeMillis() + 8000 // 8 second timeout

            while (isDiscovering && System.currentTimeMillis() < timeout) {
                try {
                    val resp = DatagramPacket(buf, buf.size)
                    discoverySocket?.receive(resp)
                    val s = String(resp.data, 0, resp.length)
                    if (s.uppercase().contains("LOCATION:")) {
                        foundServer = true
                        val locLine = s.lines().first { it.uppercase().trim().startsWith("LOCATION:") }
                        val url = locLine.substringAfter(":").trim().substringBeforeLast("/")
                        handler.post {
                            if (isShowingServers) {
                                // Remove no network message if showing
                                displayItems.removeAll { it is DisplayItem.NoNetwork }

                                // Add header if needed
                                if (displayItems.isEmpty() || displayItems.first() !is DisplayItem.Header) {
                                    displayItems.add(0, DisplayItem.Header)
                                }

                                val serverItem = DisplayItem.Server(url)
                                if (!displayItems.contains(serverItem)) {
                                    displayItems.add(serverItem)
                                    recyclerView.adapter?.notifyDataSetChanged()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }

            // If no servers found, might be network issue
            if (!foundServer) {
                handler.post {
                    if (displayItems.none { it is DisplayItem.Server }) {
                        showNoNetworkState()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("DLNA", "SSDP error: ${e.message}")
            handler.post { showNoNetworkState() }
        } finally {
            isDiscovering = false
            discoverySocket?.close()
            discoverySocket = null
        }
    }

    private fun sendBrowseRequest(baseUrl: String, objectId: String) {
        showLoadingState()

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl/ctl/ContentDir")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.doInput = true

                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")

                val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <ObjectID>$objectId</ObjectID>
      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
      <Filter>*</Filter>
      <StartingIndex>0</StartingIndex>
      <RequestedCount>5000</RequestedCount>
      <SortCriteria></SortCriteria>
    </u:Browse>
  </s:Body>
</s:Envelope>""".trimIndent()

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                Log.d("DLNA", "Object ID: ${objectId}")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    // PERFORMANCE FIX: Read entire response at once instead of char-by-char
                    val responseString = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                    Log.d("DLNA", "Object ID: ${objectId}")
                    Log.d("DLNA", "Browse Response Length: ${responseString.length}")
                    Log.d("DLNA", "Browse Response:\n$responseString")

                    parseBrowseResponse(responseString)

                } else {
                    val errorStream = conn.errorStream
                    if (errorStream != null) {
                        val errorString = errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        Log.e("DLNA", "Non-OK (${conn.responseCode}):\n$errorString")
                    } else {
                        Log.e("DLNA", "Non-OK (${conn.responseCode}): No error stream available.")
                    }

                    hideLoadingState()
                    handler.post {
                        recyclerView.adapter?.notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                Log.e("DLNA", "Exception in sendBrowseRequest", e)
                hideLoadingState()
                handler.post {
                    recyclerView.adapter?.notifyDataSetChanged()
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun sanitizeXML(xml: String): String {
        var sanitized = xml
        //val validEntities = setOf("&lt;", "&gt;", "&amp;", "&quot;", "&apos;")
        sanitized = sanitized.replace(Regex("&(?!(?:lt|gt|amp|quot|apos);)")) { "&amp;" }
        sanitized = sanitized.replace("\u0000", "")
        if (!sanitized.trim().startsWith("<")) {
            sanitized = "<DIDL-Lite>$sanitized</DIDL-Lite>"
        }
        return sanitized
    }
    private fun replaceHtmlEntities(text: String): String {
        return text.replace("&eacute;", "é")
        // Add more replacements as needed
        // .replace("&amp;", "&")
        // .replace("&quot;", "\"")
        // .replace("&apos;", "'")
        // .replace("<", "<")
        // .replace(">", ">")
    }
    private fun parseBrowseResponse(responseString: String) {
        try {
            // Switch to showing content instead of servers
            isShowingServers = false

            // Fix unescaped ampersands in the entire SOAP response first
            val fixedResponseString = responseString
                .replace("&(?![a-zA-Z0-9#]{1,7};)".toRegex(), "&amp;")
                .replace("&eacute;", "é")

            val soapDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(fixedResponseString.toByteArray()))

            val resultElements = soapDoc.getElementsByTagName("Result")
            if (resultElements.length == 0) {
                Log.e("DLNA", "No Result element found in SOAP response")
                return
            }

            val resultContent = resultElements.item(0).textContent
            Log.d("DLNA", "Raw Result content: $resultContent")

            val decodedResult = resultContent
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replace("é", "&eacute;")

            // Fix unescaped ampersands again after decoding
            val fixedResult = decodedResult
                .replace("&(?![a-zA-Z0-9#]{1,7};)".toRegex(), "&amp;")

            val sanitizedresult = sanitizeXML(fixedResult)
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(sanitizedresult.toByteArray(Charsets.UTF_8)))

            // Clear existing items first and hide loading
            handler.post {
                hideLoadingState()
                val oldSize = displayItems.size
                displayItems.clear()
                if (oldSize > 0) {
                    recyclerView.adapter?.notifyItemRangeRemoved(0, oldSize)
                }
            }

            val list = mutableListOf<DisplayItem>()

            // Extract containers (storage folders) with progressive updates
            val containers = doc.getElementsByTagName("container")
            for (i in 0 until containers.length) {
                val container = containers.item(i) as Element
                val upnpClass = container.getElementsByTagName("upnp:class").item(0)?.textContent
                if (upnpClass?.contains("object.container.storageFolder") == true) {
                    val title = container.getElementsByTagName("dc:title").item(0)?.textContent ?: "Unknown Folder"
                    val objectId = container.getAttribute("id")
                    list += DisplayItem.Container(title, objectId)

                    // Update UI every 100 containers
                    if ((i + 1) % 100 == 0) {
                        handler.post {
                            val currentSize = displayItems.size
                            displayItems.clear()
                            displayItems.addAll(list)

                            if (currentSize > 0) {
                                recyclerView.adapter?.notifyItemRangeRemoved(0, currentSize)
                            }
                            recyclerView.adapter?.notifyItemRangeInserted(0, displayItems.size)
                        }
                    }
                }
            }

            // Extract video items with progressive updates
            val items = doc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val item = items.item(i) as Element
                val upnpClass = item.getElementsByTagName("upnp:class").item(0)?.textContent
                if (upnpClass?.contains("object.item.videoItem") == true) {
                    val title = replaceHtmlEntities(item.getElementsByTagName("dc:title").item(0)?.textContent ?: "Unknown Video")
                    val objectId = item.getAttribute("id")
                    val resElement = item.getElementsByTagName("res").item(0)
                    val videoUrl = resElement?.textContent
                    list += DisplayItem.Video(title, objectId, videoUrl)

                    // Update UI every 100 video items
                    if ((i + 1) % 100 == 0) {
                        handler.post {
                            val currentSize = displayItems.size
                            displayItems.clear()
                            displayItems.addAll(list)

                            if (currentSize > 0) {
                                recyclerView.adapter?.notifyItemRangeRemoved(0, currentSize)
                            }
                            recyclerView.adapter?.notifyItemRangeInserted(0, displayItems.size)
                        }
                    }
                }
            }

            // Final update with all items
            handler.post {
                val oldSize = displayItems.size
                displayItems.clear()
                displayItems.addAll(list)

                // PERFORMANCE FIX: Use efficient range operations
                if (oldSize > 0) {
                    recyclerView.adapter?.notifyItemRangeRemoved(0, oldSize)
                }
                if (list.isNotEmpty()) {
                    recyclerView.adapter?.notifyItemRangeInserted(0, list.size)
                }
            }

        } catch (e: Exception) {
            Log.e("DLNA", "Error parsing browse response", e)
            hideLoadingState()
            handler.post {
                recyclerView.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun navigateBack() {
        Log.d("DLNA", "Before selectedDirectory $selectedDirectory")
        Log.d("DLNA", "Before selectedServerUrl $selectedServerUrl")

        if (selectedDirectory != null) {
            selectedDirectory?.let { currentDir ->
                val trimmed = currentDir.trimEnd('/')
                val lastSlashIndex = trimmed.lastIndexOf('/')
                selectedDirectory = if (lastSlashIndex >= 0) {
                    trimmed.substring(0, lastSlashIndex + 1)
                } else {
                    null // If no more slashes, go back to root (object ID "0")
                }

                selectedServerUrl?.let { serverUrl ->
                    val objectId = selectedDirectory ?: "0"
                    sendBrowseRequest(serverUrl, objectId)
                }
            }
        } else {
            // Go back to servers view
            resetToServersView()
        }

        Log.d("DLNA", "After selectedDirectory $selectedDirectory")
        Log.d("DLNA", "After selectedServerUrl $selectedServerUrl")
    }

    private fun resetToServersView() {
        selectedServerUrl = null
        selectedDirectory = null
        isShowingServers = true
        isDiscovering = false

        val oldSize = displayItems.size
        displayItems.clear()

        // PERFORMANCE FIX: Only notify about removals, let SSDP discovery handle additions
        if (oldSize > 0) {
            recyclerView.adapter?.notifyItemRangeRemoved(0, oldSize)
        }

        startSSDP()
    }
    // Sealed class to represent different types of items
    sealed class DisplayItem {
        object Header : DisplayItem()
        data class Server(val url: String) : DisplayItem()
        data class Container(val title: String, val objectId: String) : DisplayItem()
        data class Video(val title: String, val objectId: String, val url: String?) : DisplayItem()
        object Loading : DisplayItem()
        object NoNetwork : DisplayItem() // Add this line

        override fun equals(other: Any?): Boolean {
            return when {
                this is Header && other is Header -> true
                this is Server && other is Server -> this.url == other.url
                this is Container && other is Container -> this.objectId == other.objectId
                this is Video && other is Video -> this.objectId == other.objectId
                this is Loading && other is Loading -> true
                this is NoNetwork && other is NoNetwork -> true // Add this line
                else -> false
            }
        }

        override fun hashCode(): Int {
            return when (this) {
                is Header -> javaClass.hashCode()
                is Server -> url.hashCode()
                is Container -> objectId.hashCode()
                is Video -> objectId.hashCode()
                is Loading -> javaClass.hashCode()
                is NoNetwork -> javaClass.hashCode() // Add this line
            }
        }
    }

    // Update your companion object constants
    companion object {
        const val TYPE_HEADER = 0   // Add this line
        const val TYPE_SERVER = 1   // Update these numbers
        const val TYPE_ITEM = 2
        const val TYPE_LOADING = 3
        const val TYPE_NO_NETWORK = 4
        const val EXTRA_VIDEO_URLS = "com.example.rustydlnaclient.EXTRA_VIDEO_URLS"
        const val EXTRA_VIDEO_TITLES = "com.example.rustydlnaclient.EXTRA_VIDEO_TITLES"
        const val EXTRA_START_INDEX = "com.example.rustydlnaclient.EXTRA_START_INDEX"

        const val THUMBNAIL_SIZE_PX = 256
    }

    private inner class CombinedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int {
            return when (displayItems[position]) {
                is DisplayItem.Header -> TYPE_HEADER
                is DisplayItem.Server -> TYPE_SERVER
                is DisplayItem.Loading -> TYPE_LOADING
                is DisplayItem.NoNetwork -> TYPE_NO_NETWORK // Add this line
                else -> TYPE_ITEM
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_NO_NETWORK -> {
                    val tv = TextView(this@MainActivity).apply {
                        setPadding(48, 96, 48, 96)
                        textSize = 18f
                        gravity = Gravity.CENTER
                        text = "📶❌ No Network\n\nConnect to WiFi to find DLNA servers\n\n🔄 Tap to retry"
                        setTextColor(0xFFFFFFFF.toInt())
                        setBackgroundColor(0xFF333333.toInt())
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    NoNetworkViewHolder(tv)
                }
                TYPE_HEADER -> {
                    val headerLayout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(32, 48, 32, 32)
                        setBackgroundColor(0xFF1E1E1E.toInt())
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val iconView = ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                            rightMargin = 24
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageResource(R.mipmap.ic_launcher) // Replace with your app icon
                    }

                    val appNameTv = TextView(this@MainActivity).apply {
                        text = "Rusty DLNA Client"
                        textSize = 24f
                        setTextColor(0xFFFFFFFF.toInt())
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    headerLayout.addView(iconView)
                    headerLayout.addView(appNameTv)

                    HeaderViewHolder(headerLayout)
                }
                TYPE_SERVER -> {
                    val tv = TextView(this@MainActivity).apply {
                        setPadding(48, 16, 24, 16) // Extra left padding to indent servers
                        textSize = 18f
                        gravity = Gravity.CENTER_VERTICAL
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    ServerViewHolder(tv)
                }
                TYPE_LOADING -> {
                    val tv = TextView(this@MainActivity).apply {
                        setPadding(24, 48, 24, 48)
                        textSize = 20f
                        gravity = Gravity.CENTER
                        text = "Loading..."
                        setTextColor(0xFFFFFFFF.toInt()) // White text color
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    LoadingViewHolder(tv)
                }
                else -> {
                    // Modified item view holder - removed ImageView completely
                    val ll = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(24, 0, 24, 24)
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }

                    val textContainer = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, // Changed from 0 width with weight
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        // Removed leftMargin since there's no image anymore
                    }

                    val titleTv = TextView(this@MainActivity).apply {
                        textSize = 20f
                        setTextColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val progressTv = TextView(this@MainActivity).apply {
                        textSize = 14f
                        setTextColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = 8
                        }
                        visibility = View.GONE
                    }

                    textContainer.addView(titleTv)
                    textContainer.addView(progressTv)

                    ll.addView(textContainer) // Only add text container, no ImageView
                    ItemViewHolder(ll, null, titleTv, progressTv) // Pass null for ImageView
                }
            }
        }

        override fun getItemCount() = displayItems.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderViewHolder -> holder.bind()
                is ServerViewHolder -> holder.bind(displayItems[position] as DisplayItem.Server)
                is LoadingViewHolder -> holder.bind()
                is NoNetworkViewHolder -> holder.bind() // Add this line
                is ItemViewHolder -> holder.bind(displayItems[position])
            }
        }
    }
    private inner class HeaderViewHolder(val layout: LinearLayout) : RecyclerView.ViewHolder(layout) {
        fun bind() {
            // Header doesn't need click functionality, just displays app name and icon
        }
    }
    private inner class NoNetworkViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind() {
            tv.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    tv.setBackgroundColor(0xFF2196F3.toInt())
                } else {
                    tv.setBackgroundColor(0xFF333333.toInt())
                }
            }

            tv.setOnClickListener {
                // Simple retry - just restart discovery
                displayItems.clear()
                recyclerView.adapter?.notifyDataSetChanged()
                startSSDP()
            }
        }
    }
    // Your existing ServerViewHolder remains mostly the same but simplified
    private inner class ServerViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(server: DisplayItem.Server) {
            tv.text = "🖥️ ${server.url}"
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            tv.setPadding(48, 16, 24, 16) // Extra left padding to indent
            tv.setBackgroundColor(0xFF111111.toInt())
            tv.setTextColor(0xFFFFFFFF.toInt())

            tv.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            tv.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    tv.setBackgroundColor(0xFF2196F3.toInt())
                } else {
                    tv.setBackgroundColor(0xFF111111.toInt())
                }
            }

            tv.setOnClickListener {
                selectedServerUrl = server.url
                sendBrowseRequest(server.url, objectId = "0")
            }
        }
    }

    private inner class LoadingViewHolder(tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind() {
            // Loading item doesn't need any special binding
        }
    }

    private inner class ItemViewHolder(
        view: LinearLayout,
        val iv: ImageView?, // Make ImageView nullable
        val titleTv: TextView,
        val progressTv: TextView
    ) : RecyclerView.ViewHolder(view) {

        private val mainHandler = Handler(Looper.getMainLooper())

        fun bind(item: DisplayItem) {
            // Set up focus change listener for highlighting
            itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val position = bindingAdapterPosition
                    Log.d("RecyclerView", "Focused on item at position: $position")
                    itemView.background = 0xFF2196F3.toInt().toDrawable() // Blue highlight color
                } else {
                    itemView.background = 0x00000000.toDrawable() // Transparent
                }
            }

            when (item) {
                is DisplayItem.Container -> {
                    // Remove image setting since iv is now null
                    titleTv.text = "📁 ${item.title}"
                    progressTv.visibility = View.GONE
                    itemView.setOnClickListener {
                        selectedDirectory = item.objectId.trimEnd('/') + "/"
                        selectedServerUrl?.let { serverUrl ->
                            sendBrowseRequest(serverUrl, selectedDirectory!!)
                        }
                    }
                }
                is DisplayItem.Video -> {
                    titleTv.text = "🎬 ${item.title}"

                    // Remove all image-related code since iv is now null
                    item.url?.let { videoUrl ->
                        val progress = progressManager.getVideoProgress(videoUrl)
                        if (progress != null) {
                            progressTv.text = "📍${progress.getFormattedProgress()}"
                            progressTv.visibility = View.VISIBLE
                        } else {
                            progressTv.visibility = View.GONE
                        }
                    } ?: run {
                        progressTv.visibility = View.GONE
                    }

                    itemView.setOnClickListener {
                        item.url?.let {
                            val videoUrls = arrayListOf<String>()
                            val videoTitles = arrayListOf<String>()
                            var startIndex = 0

                            displayItems.filterIsInstance<DisplayItem.Video>().forEachIndexed { _, videoItem ->
                                videoItem.url?.let { url ->
                                    videoUrls.add(url)
                                    videoTitles.add(videoItem.title)
                                    if (videoItem.objectId == item.objectId) {
                                        startIndex = videoUrls.lastIndex
                                    }
                                }
                            }

                            if (videoUrls.isNotEmpty()) {
                                val intent = Intent(this@MainActivity, VideoPlayerActivity::class.java).apply {
                                    putStringArrayListExtra(EXTRA_VIDEO_URLS, videoUrls)
                                    putStringArrayListExtra(EXTRA_VIDEO_TITLES, videoTitles)
                                    putExtra(EXTRA_START_INDEX, startIndex)
                                }
                                startActivity(intent)
                            } else {
                                Toast.makeText(this@MainActivity, "No video URLs available for playlist", Toast.LENGTH_SHORT).show()
                            }
                        } ?: run {
                            Toast.makeText(this@MainActivity, "No video URL available", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> {}
            }
        }
    }
}