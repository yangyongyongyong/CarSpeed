package com.thomas.carspeed

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class RadarQueryActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var placeSuggest: PlaceSuggest
    private lateinit var routeQuery: RoutePlanQuery
    private var fillingText = false
    private var editingStart = true
    private var startPlace: PlaceTip? = null
    private var endPlace: PlaceTip? = null
    private var startAutoFilled = false
    private var userEditedStart = false
    private var locationForActiveField = false
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var routes: List<PlannedRoute> = emptyList()
    private var selectedRouteId: Int? = null

    private lateinit var etStart: TextView
    private lateinit var etEnd: TextView
    private lateinit var suggestionScroll: android.view.View
    private lateinit var suggestionList: LinearLayout
    private lateinit var btnQuery: MaterialButton
    private lateinit var routePanel: android.view.View
    private lateinit var routeChips: LinearLayout
    private lateinit var tvRadarSummary: TextView
    private lateinit var queryMap: RadarQueryMapView

    private val suggestRunnable = Runnable {
        val keyword = if (editingStart) etStart.text?.toString().orEmpty() else etEnd.text?.toString().orEmpty()
        placeSuggest.suggest(keyword) { places ->
            if (!isDestroyed) showSuggestions(places)
        }
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) useCurrentLocation(forActiveField = locationForActiveField)
        else toast(R.string.radar_query_location_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        placeSuggest = PlaceSuggest(this)
        routeQuery = RoutePlanQuery(this)
        setContentView(R.layout.activity_radar_query)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        etStart = findViewById(R.id.etStart)
        etEnd = findViewById(R.id.etEnd)
        suggestionScroll = findViewById(R.id.suggestionScroll)
        suggestionList = findViewById(R.id.suggestionList)
        btnQuery = findViewById(R.id.btnQuery)
        routePanel = findViewById(R.id.routePanel)
        routeChips = findViewById(R.id.routeChips)
        tvRadarSummary = findViewById(R.id.tvRadarSummary)
        queryMap = findViewById(R.id.queryMap)

        toolbar.setNavigationOnClickListener { finish() }
        watchField(etStart, startField = true)
        watchField(etEnd, startField = false)
        findViewById<android.view.View>(R.id.btnSwap).setOnClickListener { swapPlaces() }
        findViewById<android.view.View>(R.id.btnMyLocation).setOnClickListener { useMyLocation() }
        findViewById<android.view.View>(R.id.btnHome).apply {
            setOnClickListener { useFixedPlace(home = true) }
            setOnLongClickListener {
                editFixedPlace(home = true)
                true
            }
        }
        findViewById<android.view.View>(R.id.btnCompany).apply {
            setOnClickListener { useFixedPlace(home = false) }
            setOnLongClickListener {
                editFixedPlace(home = false)
                true
            }
        }
        findViewById<android.view.View>(R.id.btnFavorites).setOnClickListener { showFavorites() }
        btnQuery.setOnClickListener { queryRoutes() }
        etEnd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                queryRoutes()
                true
            } else {
                false
            }
        }
        queryMap.onRouteSelected = { id -> selectRoute(id) }
        queryMap.onRadarClick = { radar -> showRadar(radar) }
        useCurrentLocation(forActiveField = false)
    }

    override fun onDestroy() {
        favoritesDialog?.dismiss()
        stopLocationUpdates()
        mainHandler.removeCallbacks(suggestRunnable)
        if (::placeSuggest.isInitialized) placeSuggest.cancel()
        if (::routeQuery.isInitialized) routeQuery.cancel()
        super.onDestroy()
    }

    private fun watchField(field: TextView, startField: Boolean) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (fillingText) return
                if (startField) {
                    startPlace = null
                    startAutoFilled = false
                    userEditedStart = true
                } else {
                    endPlace = null
                }
                editingStart = startField
                scheduleSuggest()
            }
        })
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus || fillingText) return@setOnFocusChangeListener
            editingStart = startField
            val chosen = if (startField) startPlace else endPlace
            if (chosen == null) scheduleSuggest()
        }
    }

    private fun scheduleSuggest() {
        mainHandler.removeCallbacks(suggestRunnable)
        mainHandler.postDelayed(suggestRunnable, 300L)
    }

    private fun showSuggestions(places: List<PlaceTip>) {
        suggestionList.removeAllViews()
        if (places.isEmpty()) {
            suggestionScroll.visibility = android.view.View.GONE
            return
        }
        places.take(4).forEach { tip ->
            val row = TextView(this).apply {
                text = suggestionLabel(tip)
                textSize = 14f
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { choose(tip) }
            }
            suggestionList.addView(row)
        }
        suggestionScroll.visibility = android.view.View.VISIBLE
    }

    private fun suggestionLabel(tip: PlaceTip): CharSequence {
        if (tip.detail.isBlank()) {
            return tip.name
        }
        val text = SpannableString("${tip.name}\n${tip.detail}")
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_primary)),
            0,
            tip.name.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_secondary)),
            tip.name.length,
            text.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    private fun swapPlaces() {
        fillingText = true
        val startText = etStart.text?.toString().orEmpty()
        val endText = etEnd.text?.toString().orEmpty()
        val start = startPlace
        val end = endPlace
        etStart.text = endText
        etEnd.text = startText
        startPlace = end
        endPlace = start
        startAutoFilled = false
        if (start != null || end != null || startText.isNotBlank() || endText.isNotBlank()) {
            userEditedStart = true
        }
        fillingText = false
        suggestionScroll.visibility = android.view.View.GONE
        placeSuggest.cancel()
    }

    private fun choose(tip: PlaceTip) {
        fillingText = true
        val field = if (editingStart) etStart else etEnd
        if (editingStart) startPlace = tip else endPlace = tip
        field.text = tip.name
        fillingText = false
        suggestionScroll.visibility = android.view.View.GONE
        placeSuggest.cancel()
        hideKeyboard()
    }

    private fun useMyLocation() {
        useCurrentLocation(forActiveField = true)
    }

    private fun useCurrentLocation(forActiveField: Boolean) {
        if (!hasLocationPermission()) {
            locationForActiveField = forActiveField
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        locationForActiveField = forActiveField
        val last = newestLastKnown()
        if (last != null) {
            deliverFix(last)
        } else if (forActiveField && !listenForLocation()) {
            toast(R.string.radar_query_no_location)
            return
        }
        listenForLocation()
    }

    private fun deliverFix(location: Location) {
        if (isDestroyed) return
        val tip = placeFrom(location)
        if (routes.isEmpty()) {
            queryMap.showMyLocation(LatLng(tip.latitude, tip.longitude))
        }
        when (
            QueryLocation.fillAction(
                startBlank = etStart.text.isNullOrBlank(),
                hasStartPlace = startPlace != null,
                autoFilled = startAutoFilled,
                userEditedStart = userEditedStart
            )
        ) {
            StartFill.PREFILL -> {
                fillingText = true
                startPlace = tip
                etStart.text = tip.name
                fillingText = false
                startAutoFilled = true
            }
            StartFill.UPDATE -> startPlace = tip
            StartFill.LEAVE -> Unit
        }
        if (locationForActiveField) {
            locationForActiveField = false
            fillActive(tip)
        }
    }

    private fun placeFrom(location: Location): PlaceTip {
        val gcj = CoordinateConverter(this)
            .from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(location.latitude, location.longitude))
            .convert()
        return PlaceTip(
            name = getString(R.string.radar_query_my_location_name),
            detail = "",
            latitude = gcj.latitude,
            longitude = gcj.longitude,
            poiId = ""
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun newestLastKnown(): Location? {
        val manager = getSystemService(LocationManager::class.java)
        return providerNames().mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    /** 返回是否至少有一个定位源开始更新。 */
    private fun listenForLocation(): Boolean {
        if (locationListener != null) return true
        val manager = getSystemService(LocationManager::class.java)
        val listener = LocationListener { location -> deliverFix(location) }
        var started = false
        for (provider in providerNames()) {
            val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            if (!enabled) continue
            val requested = runCatching {
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
            }.isSuccess
            started = started || requested
        }
        if (!started) return false
        locationManager = manager
        locationListener = listener
        return true
    }

    private fun stopLocationUpdates() {
        val listener = locationListener ?: return
        runCatching { locationManager?.removeUpdates(listener) }
        locationListener = null
    }

    private fun providerNames(): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    }

    private fun fillActive(tip: PlaceTip) {
        editingStart = activeIsStart()
        choose(tip)
    }

    private fun activeIsStart(): Boolean = when {
        etEnd.hasFocus() -> false
        etStart.hasFocus() -> true
        else -> editingStart
    }

    private fun activePlace(): PlaceTip? = if (activeIsStart()) startPlace else endPlace

    private fun useFixedPlace(home: Boolean) {
        val saved = loadFixed(home)
        if (saved == null) {
            editFixedPlace(home)
            return
        }
        fillActive(saved)
    }

    private fun editFixedPlace(home: Boolean) {
        val current = activePlace()
        val saved = loadFixed(home)
        val label = getString(if (home) R.string.radar_query_home_label else R.string.radar_query_company_label)
        if (current == null && saved == null) {
            toast(R.string.radar_query_need_chosen_place)
            return
        }
        val message = buildString {
            if (current != null) append(getString(R.string.radar_query_set_place_message, current.name, label))
            if (saved != null) {
                if (isNotEmpty()) append('\n')
                append(getString(R.string.radar_query_set_place_current, saved.name))
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
        if (current != null) {
            dialog.setPositiveButton(R.string.radar_query_set_place_confirm) { _, _ ->
                saveFixed(home, current)
                toast(R.string.radar_query_set_place_saved)
            }
        }
        if (saved != null) {
            dialog.setNeutralButton(R.string.radar_query_set_place_clear) { _, _ ->
                saveFixed(home, null)
                toast(R.string.radar_query_set_place_cleared)
            }
        }
        dialog.show()
    }

    private fun showFavorites() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val current = activePlace()
        val favorites = loadFavorites()
        val alreadySaved = current != null && favorites.any { SavedPlaces.samePlace(it, current) }
        if (current != null && !alreadySaved) {
            content.addView(TextView(this).apply {
                text = getString(R.string.radar_query_favorite_add, current.name)
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@RadarQueryActivity, R.color.brand_blue))
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener {
                    val next = SavedPlaces.addFavorite(loadFavorites(), current)
                    if (!next.added) {
                        toast(R.string.radar_query_favorite_exists)
                        return@setOnClickListener
                    }
                    saveFavorites(next.favorites)
                    toast(R.string.radar_query_favorite_added)
                    favoritesDialog?.dismiss()
                    showFavorites()
                }
            })
        }
        if (favorites.isEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.radar_query_favorite_empty)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@RadarQueryActivity, R.color.text_secondary))
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
        }
        favorites.forEach { place ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = if (place.detail.isBlank()) place.name else "${place.name}\n${place.detail}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@RadarQueryActivity, R.color.text_primary))
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener {
                    favoritesDialog?.dismiss()
                    fillActive(place)
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = getString(R.string.radar_query_favorite_delete)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@RadarQueryActivity, R.color.text_danger))
                setPadding(dp(8), dp(10), dp(4), dp(10))
                setOnClickListener {
                    saveFavorites(loadFavorites().filterNot { SavedPlaces.samePlace(it, place) })
                    favoritesDialog?.dismiss()
                    showFavorites()
                }
            })
            content.addView(row)
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val capped = MeasureSpec.makeMeasureSpec(dp(360), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, capped)
            }
        }.apply { addView(content) }
        favoritesDialog = AlertDialog.Builder(this)
            .setTitle(R.string.radar_query_favorites_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var favoritesDialog: AlertDialog? = null

    private fun placePrefs() = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)

    private fun loadFixed(home: Boolean): PlaceTip? {
        val key = if (home) SavedPlaces.KEY_HOME else SavedPlaces.KEY_COMPANY
        return SavedPlaces.decode(placePrefs().getString(key, null))
    }

    private fun saveFixed(home: Boolean, place: PlaceTip?) {
        val key = if (home) SavedPlaces.KEY_HOME else SavedPlaces.KEY_COMPANY
        placePrefs().edit().putString(key, place?.let(SavedPlaces::encode)).apply()
    }

    private fun loadFavorites(): List<PlaceTip> =
        SavedPlaces.decodeFavorites(placePrefs().getString(SavedPlaces.KEY_FAVORITES, null))

    private fun saveFavorites(places: List<PlaceTip>) {
        placePrefs().edit().putString(SavedPlaces.KEY_FAVORITES, SavedPlaces.encodeFavorites(places)).apply()
    }

    private fun queryRoutes() {
        val start = startPlace
        val end = endPlace
        if (start == null || end == null) {
            toast(R.string.radar_query_need_place)
            return
        }
        hideKeyboard()
        suggestionScroll.visibility = android.view.View.GONE
        btnQuery.isEnabled = false
        btnQuery.setText(R.string.radar_query_searching)
        routeQuery.calculate(
            start = start,
            end = end,
            onSuccess = { found ->
                if (isDestroyed) return@calculate
                btnQuery.isEnabled = true
                btnQuery.setText(R.string.radar_query_action)
                routes = found
                selectedRouteId = found.first().id
                renderSelection()
            },
            onError = { error ->
                if (isDestroyed) return@calculate
                btnQuery.isEnabled = true
                btnQuery.setText(R.string.radar_query_action)
                val message = when (error) {
                    RoutePlanError.NO_ROUTE -> R.string.radar_query_empty
                    RoutePlanError.FAILED -> R.string.radar_query_failed
                }
                toast(message)
            }
        )
    }

    private fun selectRoute(id: Int) {
        if (routes.none { it.id == id } || selectedRouteId == id) return
        selectedRouteId = id
        renderSelection()
    }

    private fun renderSelection() {
        val selectedId = selectedRouteId ?: return
        val start = startPlace ?: return
        val end = endPlace ?: return
        routePanel.visibility = android.view.View.VISIBLE
        routeChips.removeAllViews()
        routes.forEachIndexed { index, route ->
            val selected = route.id == selectedId
            val chip = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = RoutePlanFormat.chip(
                    label = route.label,
                    index = index,
                    durationSeconds = route.durationSeconds,
                    distanceMeters = route.distanceMeters,
                    tollYuan = route.tollYuan,
                    radarCount = route.radars.size
                )
                isAllCaps = false
                textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                cornerRadius = dp(12)
                strokeWidth = if (selected) 0 else dp(1)
                val background = if (selected) R.color.brand_blue else R.color.surface_primary
                val textColor = if (selected) R.color.white else R.color.text_primary
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@RadarQueryActivity, background))
                setTextColor(ContextCompat.getColor(this@RadarQueryActivity, textColor))
                strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this@RadarQueryActivity, R.color.divider_soft))
                setOnClickListener { selectRoute(route.id) }
            }
            routeChips.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
        }
        val selected = routes.first { it.id == selectedId }
        tvRadarSummary.text = RoutePlanFormat.summary(selected.radars)
        queryMap.show(
            routes = routes,
            selectedId = selectedId,
            start = LatLng(start.latitude, start.longitude),
            end = LatLng(end.latitude, end.longitude)
        )
    }

    private fun showRadar(radar: VideoTrackRadar) {
        AlertDialog.Builder(this)
            .setTitle(R.string.route_radar_detail_title)
            .setMessage(
                RouteRadarMarks.detail(
                    cameraType = radar.cameraType,
                    speedLimit = radar.speedLimit,
                    roadLayer = radar.roadLayer,
                    usingDefault = radar.usingDefault,
                    correctedByDivergence = radar.correctedByDivergence
                )
            )
            .setPositiveButton(R.string.route_radar_detail_close, null)
            .show()
    }

    private fun hideKeyboard() {
        val focused = currentFocus ?: return
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(focused.windowToken, 0)
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
