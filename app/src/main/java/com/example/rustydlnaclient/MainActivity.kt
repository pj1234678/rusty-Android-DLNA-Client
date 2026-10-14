// File: MainActivity.kt
package com.example.rustydlnaclient

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import android.content.Context
import android.net.ConnectivityManager
import com.example.rustydlnaclient.perf.BrowseXml
import com.example.rustydlnaclient.perf.PlaybackBenchmark
import java.text.SimpleDateFormat
import java.util.Locale
class MainActivity : ComponentActivity() {
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        return activeNetworkInfo != null && activeNetworkInfo.isConnected
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var clockTv: TextView
    private val displayItems = mutableListOf<DisplayItem>()
    private val handler = Handler(Looper.getMainLooper())

    // Fixed top-bar clock (date above 12h time, ticks once per minute).
    private val clockDateFormat = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
    private val clockTimeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private var clockRunnable: Runnable? = null

    // Add VideoProgressManager
    private lateinit var progressManager: VideoProgressManager

    // Buffer-profile override for A/B runs (set via launching intent extra,
    // forwarded to VideoPlayerActivity; null = B_balanced default).
    private var benchProfile: String? = null

    private var selectedServerUrl: String? = null
    private var selectedDirectory: String? = null
    private var isShowingServers = true
    private var isLoading = false

    // RACE FIX: monotonic browse generation. Written only on UI thread.
    // Every sendBrowseRequest captures its seq; every handler.post that touches
    // displayItems/isShowingServers/Loading drops if stale (mySeq != browseSeq).
    @Volatile
    private var browseSeq = 0
    // RACE + $ID FIX: exact navigation stack of raw ObjectIDs (e.g. "0", "64", "64$5").
    // Source of truth for Back; selectedDirectory kept as mirror for logs/compat.
    // Never derive parent via lastIndexOf('/') — opaque IDs use '$', not '/'.
    private val navStack = ArrayDeque<String>()
    // FOCUS FIX: entering a folder/server must always land focus on the first
    // item (page jumps otherwise leave focus deep in the new list). Set on
    // forward navigation clicks, consumed by the browse final sync, cleared
    // on back navigation. UI thread only.
    private var focusFirstOnNextBrowse = false

    // Add flag to track if SSDP discovery is running
    private var isDiscovering = false
    private var discoverySocket: DatagramSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize progress manager
        progressManager = VideoProgressManager(this)
        // Logcat-only browse benchmarks (no-op on non-debuggable builds).
        PlaybackBenchmark.ensureEnabled(this)
        benchProfile = intent.getStringExtra(VideoPlayerActivity.EXTRA_BUFFER_PROFILE)

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

        // Fixed top bar: always visible above the list (servers AND folders/files).
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(0xFF1E1E1E.toInt())
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                rightMargin = 24
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.mipmap.ic_launcher)
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val appNameTv = TextView(this).apply {
            text = "Rusty DLNA Client"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            isFocusable = false
            isFocusableInTouchMode = false
        }

        clockTv = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 24
            }
        }
        updateClockDisplay()

        topBar.addView(iconView)
        topBar.addView(appNameTv)
        topBar.addView(spacer)
        topBar.addView(clockTv)
        layout.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            addItemDecoration(DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL))
            adapter = CombinedAdapter()
            itemAnimator = null
            // PERF: this list fills its parent (weight=1), so its own size
            // never depends on adapter content — skip requestLayout passes
            // on every insert/remove batch.
            setHasFixedSize(true)
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
            0,
            1f
        ))

        setContentView(layout)

        // Setup modern back navigation
        setupBackNavigation()

        // Initialize the app state
        initializeApp()

        // PERF: pre-warm one ExoPlayer on the main thread now; the cost hides
        // inside SSDP browsing so the first video skips Builder + thread +
        // renderers-factory spin-up. No-op if the pool already holds one.
        handler.post {
            if (!isDestroyed && !isFinishing) {
                PlayerHolder.warmup(
                    applicationContext,
                    com.example.rustydlnaclient.perf.PlayerConfigProfiles.resolve(benchProfile),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startClockUpdates()
        // If we're showing servers but have no items, restart discovery
        if (isShowingServers && displayItems.isEmpty() && !isDiscovering) {
            startSSDP()
        }
        // Refresh when returning from video player (progress may have changed
        // in the player's own VideoProgressManager instance).
        if (!isShowingServers && displayItems.isNotEmpty()) {
            // PERF: drop the read cache first so rebinds see fresh values,
            // then rebind only the visible window — off-screen rows bind
            // fresh on scroll anyway. One range op instead of N item ops.
            progressManager.invalidateCache()
            val lm = recyclerView.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            val last = lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
            if (lm != null && first != RecyclerView.NO_POSITION &&
                last != RecyclerView.NO_POSITION && first <= last
            ) {
                recyclerView.adapter?.notifyItemRangeChanged(
                    first.coerceAtLeast(0),
                    (last.coerceAtMost(displayItems.size - 1) - first.coerceAtLeast(0) + 1)
                        .coerceAtLeast(1),
                )
            } else {
                recyclerView.adapter?.notifyItemRangeChanged(0, displayItems.size)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            if (handlePageKey(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // D-pad Left/Right: jump to the top/bottom of the CURRENTLY VISIBLE screen,
    // never to the absolute start/end of the entire list. A repeat press while
    // already at the visible edge pages by one screenful. Instant (no animation).
    private fun handlePageKey(keyCode: Int): Boolean {
        if (!::recyclerView.isInitialized) return false
        val itemCount = recyclerView.adapter?.itemCount ?: return false
        if (itemCount <= 1) return false
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastVisible = lm.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return false

        val focusedChild = recyclerView.focusedChild
        val focusedPos = if (focusedChild != null) {
            recyclerView.getChildAdapterPosition(focusedChild)
        } else {
            RecyclerView.NO_POSITION
        }
        val pageSize = maxOf(1, lastVisible - firstVisible)
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                focusedPos == RecyclerView.NO_POSITION -> lastVisible
                focusedPos < lastVisible -> lastVisible
                else -> (lastVisible + pageSize).coerceAtMost(itemCount - 1)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                focusedPos == RecyclerView.NO_POSITION -> firstVisible
                focusedPos > firstVisible -> firstVisible
                else -> (firstVisible - pageSize).coerceAtLeast(0)
            }
            else -> return false
        }.coerceIn(0, itemCount - 1)

        // Already there: consume so focus never escapes the list.
        if (target == focusedPos) return true

        val existing = lm.findViewByPosition(target)
        if (existing != null) {
            existing.requestFocus()
        } else {
            recyclerView.scrollToPosition(target)
            recyclerView.post {
                if (isDestroyed || isFinishing) return@post
                val count = recyclerView.adapter?.itemCount ?: return@post
                if (count == 0) return@post
                val safe = target.coerceIn(0, count - 1)
                (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.findViewByPosition(safe)?.requestFocus()
            }
        }
        return true
    }

    private fun initializeApp() {
        // Reset state to initial values
        // Invalidate any in-flight browse from a prior instance/flow.
        browseSeq++
        navStack.clear()
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
        stopClockUpdates()
        // Stop discovery when paused to avoid resource leaks
        stopSSDP()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopClockUpdates()
        stopSSDP()
        // Free the parked player only on real finish (rotation keeps reuse).
        if (isFinishing) {
            PlayerHolder.release()
        }
    }

    private fun updateClockDisplay() {
        if (::clockTv.isInitialized && !isDestroyed && !isFinishing) {
            val now = java.util.Date()
            clockTv.text = "${clockDateFormat.format(now)}\n${clockTimeFormat.format(now)}"
        }
    }

    private fun startClockUpdates() {
        stopClockUpdates()
        if (clockRunnable == null) {
            clockRunnable = object : Runnable {
                override fun run() {
                    if (isDestroyed || isFinishing) return
                    updateClockDisplay()
                    // Align to the next minute boundary (live, rounded to the minute).
                    val delay = 60_000L - (System.currentTimeMillis() % 60_000L)
                    handler.postDelayed(this, delay)
                }
            }
        }
        updateClockDisplay()
        val initialDelay = 60_000L - (System.currentTimeMillis() % 60_000L)
        handler.postDelayed(clockRunnable!!, initialDelay)
    }

    private fun stopClockUpdates() {
        clockRunnable?.let { handler.removeCallbacks(it) }
    }
    private fun showNoNetworkState() {
        val oldSize = displayItems.size
        displayItems.clear()

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
    // RACE FIX: owned by mySeq — stale requests must not clear current list.
    private fun showLoadingState(mySeq: Int) {
        handler.post {
            if (mySeq != browseSeq) {
                Log.d("DLNA", "drop stale showLoading seq=$mySeq cur=$browseSeq")
                return@post
            }
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
    // RACE FIX: only the current request may remove the Loading row.
    private fun hideLoadingState(mySeq: Int) {
        handler.post {
            if (mySeq != browseSeq) {
                Log.d("DLNA", "drop stale hideLoading seq=$mySeq cur=$browseSeq")
                return@post
            }
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
                                // (top bar is now a fixed view above the list, not a list item).
                                displayItems.removeAll { it is DisplayItem.NoNetwork }

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
        // RACE FIX: capture generation + snapshots on UI thread. Callers must have
        // already updated navStack/selectedDirectory/selectedServerUrl synchronously.
        val mySeq = ++browseSeq
        val reqUrl = baseUrl
        val reqId = objectId
        val tReq0 = SystemClock.elapsedRealtime()
        Log.d("DLNA", "issue browse seq=$mySeq id=$reqId url=$reqUrl")
        showLoadingState(mySeq)

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$reqUrl/ctl/ContentDir")
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
      <ObjectID>$reqId</ObjectID>
      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
      <Filter>*</Filter>
      <StartingIndex>0</StartingIndex>
      <RequestedCount>5000</RequestedCount>
      <SortCriteria></SortCriteria>
    </u:Browse>
  </s:Body>
</s:Envelope>""".trimIndent()

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                Log.d("DLNA", "Object ID: ${reqId} seq=$mySeq")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    // PERFORMANCE FIX: Read entire response at once instead of char-by-char
                    val responseString = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val tRead1 = SystemClock.elapsedRealtime()

                    Log.d("DLNA", "Object ID: ${reqId} seq=$mySeq")
                    Log.d("DLNA", "Browse Response Length: ${responseString.length} seq=$mySeq")
                    if (PlaybackBenchmark.enabled) {
                        Log.d("DLNA", "Browse Response:\n$responseString")
                    }

                    parseBrowseResponse(responseString, mySeq, reqUrl, reqId, tReq0, tRead1)

                } else {
                    val errorStream = conn.errorStream
                    if (errorStream != null) {
                        val errorString = errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        Log.e("DLNA", "Non-OK (${conn.responseCode}) seq=$mySeq:\n$errorString")
                    } else {
                        Log.e("DLNA", "Non-OK (${conn.responseCode}) seq=$mySeq: No error stream available.")
                    }

                    if (mySeq != browseSeq) {
                        Log.d("DLNA", "drop stale browse-error seq=$mySeq cur=$browseSeq req=$reqId")
                        return@Thread
                    }
                    hideLoadingState(mySeq)
                    handler.post {
                        if (mySeq != browseSeq) return@post
                        recyclerView.adapter?.notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                Log.e("DLNA", "Exception in sendBrowseRequest seq=$mySeq", e)
                if (mySeq != browseSeq) {
                    Log.d("DLNA", "drop stale browse-exception seq=$mySeq cur=$browseSeq req=$reqId")
                    return@Thread
                }
                hideLoadingState(mySeq)
                handler.post {
                    if (mySeq != browseSeq) return@post
                    recyclerView.adapter?.notifyDataSetChanged()
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
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
    private fun parseBrowseResponse(
        responseString: String,
        mySeq: Int,
        reqUrl: String,
        reqId: String,
        tReq0: Long,
        tRead1: Long,
    ) {
        try {
            // RACE FIX: skip expensive XML parse for stale responses (cheap bg check).
            if (mySeq != browseSeq) {
                Log.d("DLNA", "drop stale parse-skip seq=$mySeq cur=$browseSeq req=$reqId")
                return
            }
            val tParse0 = SystemClock.elapsedRealtime()

            // FAST PATH: indexOf-extract the <Result> payload (zero regex),
            // skipping the envelope DOM parse + whole-response entity repair.
            // Falls back to DOM when there is no unique match.
            val fastPayload = BrowseXml.extractResultPayloadFast(responseString)
            val resultContent: String
            if (fastPayload != null) {
                resultContent = fastPayload
            } else {
                // Fix unescaped ampersands in the entire SOAP response first
                val fixedResponseString = BrowseXml.fixBareAmpersands(responseString)
                    .replace("&eacute;", "é")

                val soapDoc = try {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(ByteArrayInputStream(fixedResponseString.toByteArray()))
                } catch (e: Exception) {
                    BrowseXml.dropBuilder()
                    throw e
                }

                val resultElements = soapDoc.getElementsByTagName("Result")
                if (resultElements.length == 0) {
                    Log.e("DLNA", "No Result element found in SOAP response seq=$mySeq")
                    if (mySeq != browseSeq) {
                        Log.d("DLNA", "drop stale empty-result seq=$mySeq cur=$browseSeq req=$reqId")
                        return
                    }
                    hideLoadingState(mySeq)
                    handler.post {
                        if (mySeq != browseSeq) return@post
                        recyclerView.adapter?.notifyDataSetChanged()
                    }
                    return
                }

                resultContent = resultElements.item(0).textContent
            }
            val tSoap = SystemClock.elapsedRealtime()
            // PERF: full-payload dumps are logcat-gated (they cost string
            // building + logcat even when unread). Length/timing rows stay.
            if (PlaybackBenchmark.enabled) {
                Log.d("DLNA", "Raw Result content: $resultContent")
            }
            val tLog = SystemClock.elapsedRealtime()

            // STREAMING PATH: fused decode+amp-fix+sanitize (two O(n)
            // hand-rolled passes, zero regex) then a streaming DIDL scan
            // (zero DOM, zero whole-string copies).
            val decodedFixed = BrowseXml.decodeAndFix(resultContent)
            val tDec = SystemClock.elapsedRealtime()

            val (rawContainers, rawVideos) = BrowseXml.parseDidl(decodedFixed)
            val tDidl = SystemClock.elapsedRealtime()

            // Clear existing items first and hide loading
            // RACE FIX: only current seq may flip isShowingServers / clear list.
            handler.post {
                if (mySeq != browseSeq) {
                    Log.d("DLNA", "drop stale initial-clear seq=$mySeq cur=$browseSeq req=$reqId")
                    return@post
                }
                // Switch to showing content instead of servers (guarded: stale must not flip after resetToServersView)
                isShowingServers = false
                hideLoadingState(mySeq)
                val oldSize = displayItems.size
                displayItems.clear()
                if (oldSize > 0) {
                    recyclerView.adapter?.notifyItemRangeRemoved(0, oldSize)
                }
            }

            val list = mutableListOf<DisplayItem>()
            var progPosts = 0
            var postedCount = 0

            // Delta-append helper: posts only new entries, snapshotted on this
            // thread (the live list keeps growing here, so never post it).
            // The final post below re-syncs the full list, so any interleaving
            // with the initial clear is transient and self-healing.
            fun postDelta() {
                val from = postedCount
                if (from >= list.size) return
                val delta = ArrayList(list.subList(from, list.size))
                postedCount = list.size
                progPosts++
                handler.post {
                    if (mySeq != browseSeq) {
                        Log.d("DLNA", "drop stale progressive seq=$mySeq cur=$browseSeq req=$reqId")
                        return@post
                    }
                    val pos = displayItems.size
                    displayItems.addAll(delta)
                    recyclerView.adapter?.notifyItemRangeInserted(pos, delta.size)
                }
            }

            // Map streaming entries (containers-first order preserved) with
            // progressive updates. Filters/fallbacks identical to the DOM path.
            var containerCount = 0
            var videoCount = 0
            for (e in rawContainers) {
                if (e.upnpClass?.contains("object.container.storageFolder") == true) {
                    list += DisplayItem.Container(e.title ?: "Unknown Folder", e.id)
                    containerCount++

                    // Delta update every 500 entries (was: full rebuild every 100).
                    if (list.size % 500 == 0) postDelta()
                }
            }

            for (e in rawVideos) {
                if (e.upnpClass?.contains("object.item.videoItem") == true) {
                    val title = replaceHtmlEntities(e.title ?: "Unknown Video")
                    list += DisplayItem.Video(title, e.id, e.res)
                    videoCount++

                    if (list.size % 500 == 0) postDelta()
                }
            }

            // Final update with all items
            // RACE FIX: last-writer-wins replaced by current-writer-wins.
            val tExtract = SystemClock.elapsedRealtime()
            handler.post {
                if (mySeq != browseSeq) {
                    Log.d("DLNA", "drop stale final seq=$mySeq cur=$browseSeq req=$reqId")
                    return@post
                }
                isShowingServers = false
                val tUi0 = SystemClock.elapsedRealtime()
                // PERF: progressive deltas were posted in order after the
                // initial clear (same seq, FIFO handler), so when every entry
                // was posted (postedCount == list.size) and nothing else is
                // in the list, displayItems already equals list — skip the
                // clear + full reinsert, which would rebind every visible row
                // a second time (visible flash on big folders).
                if (displayItems.size == list.size && postedCount == list.size) {
                    // Already converged: no UI churn.
                } else {
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
                // FOCUS FIX: folder/server entry always lands on the first item.
                // Consumed here (once per browse); back navigation cleared the
                // flag so it keeps default focus behavior. Double post: the
                // inner runnable runs after the insert layout pass, so position
                // 0 has a laid-out view to take focus.
                if (focusFirstOnNextBrowse) {
                    focusFirstOnNextBrowse = false
                    if (list.isNotEmpty()) {
                        recyclerView.scrollToPosition(0)
                        recyclerView.post {
                            recyclerView.post {
                                if (mySeq != browseSeq || isDestroyed || isFinishing) return@post
                                (recyclerView.layoutManager as? LinearLayoutManager)
                                    ?.findViewByPosition(0)?.requestFocus()
                            }
                        }
                    }
                }
                PlaybackBenchmark.xmlRow(
                    reqId = reqId,
                    seq = mySeq,
                    respChars = responseString.length,
                    netMs = tRead1 - tReq0,
                    soapMs = tSoap - tParse0,
                    didlMs = tDidl - tSoap,
                    extractMs = tExtract - tDidl,
                    containers = containerCount,
                    videos = videoCount,
                    progPosts = progPosts,
                    uiMs = SystemClock.elapsedRealtime() - tUi0,
                    gapMs = tParse0 - tRead1,
                    logMs = tLog - tSoap,
                    decMs = tDec - tLog,
                    fixMs = 0,
                    sanMs = 0,
                    parseMs = tDidl - tDec,
                    didChars = decodedFixed.length.toLong(),
                )
            }

        } catch (e: Exception) {
            Log.e("DLNA", "Error parsing browse response seq=$mySeq req=$reqId", e)
            if (mySeq != browseSeq) {
                Log.d("DLNA", "drop stale parse-error seq=$mySeq cur=$browseSeq req=$reqId")
                return
            }
            hideLoadingState(mySeq)
            handler.post {
                if (mySeq != browseSeq) return@post
                recyclerView.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun navigateBack() {
        // Back navigation keeps default focus behavior (no force-to-first).
        focusFirstOnNextBrowse = false
        Log.d("DLNA", "Before selectedDirectory $selectedDirectory navStack=$navStack")
        Log.d("DLNA", "Before selectedServerUrl $selectedServerUrl")

        // RACE + $ID FIX: pop exact raw ObjectID stack. No lastIndexOf('/') surgery —
        // opaque IDs (e.g. "64", "64$5") have no '/' and must not be truncated.
        if (navStack.isNotEmpty()) {
            navStack.removeLast()
            val parentId = navStack.lastOrNull()
            if (parentId == null) {
                // Stack emptied -> back to servers view (poisons in-flight browses).
                Log.d("DLNA", "navigateBack: stack empty, resetting to servers")
                resetToServersView()
            } else {
                // Mirror for logs/compat: null at root "0", else raw parent id.
                selectedDirectory = if (parentId == "0") null else parentId
                selectedServerUrl?.let { serverUrl ->
                    sendBrowseRequest(serverUrl, parentId)
                }
            }
        } else {
            // Already at servers view (selectedDirectory==null and empty stack)
            if (selectedDirectory != null) {
                // Legacy fallback: should not happen once stack is source of truth,
                // but keep resync — clear to servers rather than slash-truncate.
                Log.w("DLNA", "navigateBack: empty stack but selectedDirectory=$selectedDirectory, resync to servers")
            }
            resetToServersView()
        }

        Log.d("DLNA", "After selectedDirectory $selectedDirectory navStack=$navStack")
        Log.d("DLNA", "After selectedServerUrl $selectedServerUrl")
    }

    private fun resetToServersView() {
        // RACE FIX: poison any in-flight browse so late responses drop.
        browseSeq++
        focusFirstOnNextBrowse = false
        navStack.clear()
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
    // NOTE: the top bar (app name + clock) is now a fixed view above the list,
    // so there is intentionally no Header list item.
    sealed class DisplayItem {
        data class Server(val url: String) : DisplayItem()
        data class Container(val title: String, val objectId: String) : DisplayItem()
        data class Video(val title: String, val objectId: String, val url: String?) : DisplayItem()
        object Loading : DisplayItem()
        object NoNetwork : DisplayItem() // Add this line

        override fun equals(other: Any?): Boolean {
            return when {
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
        const val TYPE_SERVER = 1   // Update these numbers
        const val TYPE_ITEM = 2
        const val TYPE_LOADING = 3
        const val TYPE_NO_NETWORK = 4
        const val EXTRA_VIDEO_URLS = "com.example.rustydlnaclient.EXTRA_VIDEO_URLS"
        const val EXTRA_VIDEO_TITLES = "com.example.rustydlnaclient.EXTRA_VIDEO_TITLES"
        const val EXTRA_START_INDEX = "com.example.rustydlnaclient.EXTRA_START_INDEX"

    }

    private inner class CombinedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int {
            return when (displayItems[position]) {
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
                is ServerViewHolder -> holder.bind(displayItems[position] as DisplayItem.Server)
                is LoadingViewHolder -> holder.bind()
                is NoNetworkViewHolder -> holder.bind() // Add this line
                is ItemViewHolder -> holder.bind(displayItems[position])
            }
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
                // RACE + $ID FIX: fresh nav history per server. Raw "0", no trailing "/".
                navStack.clear()
                navStack.addLast("0")
                selectedDirectory = null
                // FOCUS FIX: entering a server lands on the first item.
                focusFirstOnNextBrowse = true
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
                        // RACE + $ID FIX: push raw ObjectID (e.g. "64", "64$5").
                        // No trimEnd('/')+"/" hack — server expects raw ID in <ObjectID>.
                        val rawId = item.objectId
                        navStack.addLast(rawId)
                        selectedDirectory = rawId
                        // FOCUS FIX: entering a folder lands on the first item.
                        focusFirstOnNextBrowse = true
                        selectedServerUrl?.let { serverUrl ->
                            sendBrowseRequest(serverUrl, rawId)
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
                                    benchProfile?.let { putExtra(VideoPlayerActivity.EXTRA_BUFFER_PROFILE, it) }
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