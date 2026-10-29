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
import androidx.core.view.updatePadding
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
    private var resumeLocationUpdates = false
    private var locationListener: LocationListener? = null
    private var routes: List<PlannedRoute> = emptyList()
    private var selectedRouteId: Int? = null
    /** 地图上当前画着的那组路线。换路线时靠它判断是「只换线」还是「重新铺开」。 */
    private var shownRouteSet: List<PlannedRoute>? = null
    /** 地图上长按选出、还没确认的点。 */
    private var pickedPlace: PlaceTip? = null
    /** 这个点是要填给起点还是终点。点选时定下，确认时不再改。 */
    private var pickedForStart = false

    private lateinit var etStart: TextView
    private lateinit var etEnd: TextView
    private lateinit var suggestionScroll: android.view.View
    private lateinit var suggestionList: LinearLayout
    private lateinit var btnQuery: MaterialButton
    private lateinit var routePanel: android.view.View
    private lateinit var routeChips: LinearLayout
    private lateinit var tvRadarSummary: TextView
    private lateinit var queryMap: RadarQueryMapView
    private lateinit var mapPickBar: android.view.View
    private lateinit var tvMapPicked: TextView
    private lateinit var btnOpenAmap: MaterialButton

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
        mapPickBar = findViewById(R.id.mapPickBar)
        tvMapPicked = findViewById(R.id.tvMapPicked)
        btnOpenAmap = findViewById(R.id.btnOpenAmap)
        findViewById<android.view.View>(R.id.btnMapPickConfirm).setOnClickListener { confirmMapPick() }
        findViewById<android.view.View>(R.id.btnMapPickCancel).setOnClickListener { cancelMapPick() }
        btnOpenAmap.setOnClickListener { openInAmap() }

        toolbar.setNavigationOnClickListener { finish() }
        // 顶栏压矮了，但这个界面是画到状态栏下面的：固定 40dp 会被状态栏压住。
        // 把状态栏高度加到顶上，标题和返回键才落在状态栏下方。
        applyStatusBarInset(toolbar)
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
        queryMap.onMapPicked = { position -> onMapPicked(position) }
        queryMap.onPoiPicked = { poi -> onPoiPicked(poi) }
        // 起点自动填当前位置，所以用户真正要输入的一直是终点。
        // 默认就把光标放在终点框：这时候在地图上点一下/长按，落点自然是终点。
        // 延后一帧再要焦点，否则会被首帧的输入法状态抢回去。
        etEnd.post {
            if (isDestroyed) return@post
            etEnd.requestFocus()
        }
        useCurrentLocation(forActiveField = false)
    }

    /**
     * 在地图上长按选一个点。填到当前正在编辑的那个框里——
     * 用户在终点框里点过，就是想选终点，不需要再问一遍。
     */
    private fun onMapPicked(position: LatLng) {
        queryMap.showPicked(position)
        // 先把坐标本身填进去。反查地名要等一次网络请求，
        // 等它回来再填的话，用户点完会有好几秒看不出任何反应。
        offerPickedPlace(
            PlaceTip(
                name = getString(R.string.radar_query_map_picked_name),
                detail = "",
                latitude = position.latitude,
                longitude = position.longitude,
                poiId = ""
            )
        )
        placeSuggest.describePlace(position.latitude, position.longitude) { place ->
            if (isDestroyed || place == null) return@describePlace
            offerPickedPlace(
                PlaceTip(
                    name = place.name,
                    detail = "",
                    latitude = position.latitude,
                    longitude = position.longitude,
                    poiId = ""
                )
            )
        }
    }

    /**
     * 点地图上现成的地名。POI 自带上名字，不用再反查——
     * 直接给名字比反查出来的「某某路几号」更好认。
     */
    private fun onPoiPicked(poi: RadarQueryMapView.PickedPoi) {
        val name = poi.name.ifBlank { getString(R.string.radar_query_map_picked_name) }
        queryMap.showPicked(LatLng(poi.latitude, poi.longitude))
        offerPickedPlace(
            PlaceTip(
                name = name,
                detail = "",
                latitude = poi.latitude,
                longitude = poi.longitude,
                poiId = ""
            )
        )
        // 高德的 Poi 有时候只给名字不给地址。名字太短（「停车场」「超市」这种）
        // 光看名字认不出是哪个，补一个地址。名字够长就不折腾了，免得把用户选的覆盖掉。
        if (poi.name.trim().length >= MIN_POI_NAME_LENGTH) return
        placeSuggest.describePlace(poi.latitude, poi.longitude) { place ->
            if (isDestroyed || place == null) return@describePlace
            val current = pickedPlace
            // 反查期间用户又点了别的地方，就别把新选的覆盖了。
            if (current == null || current.latitude != poi.latitude || current.longitude != poi.longitude) {
                return@describePlace
            }
            offerPickedPlace(
                PlaceTip(
                    name = name,
                    detail = place.detail,
                    latitude = poi.latitude,
                    longitude = poi.longitude,
                    poiId = ""
                )
            )
        }
    }

    /**
     * 在地图上点/长按选出的点，直接写进输入框。
     *
     * 之前是先摆到一条「确认条」上、再点确认才填。两个毛病：
     * 再点一个点的时候，确认条上的文字更新了但输入框还是旧的；
     * 而且第 2~N 次点选时，第一次的确认条已经收起，用户眼前看不出变化。
     * 选点本来就是在指定终点，没有需要再确认的东西，所以改成点完即填。
     */
    private fun offerPickedPlace(tip: PlaceTip) {
        pickedPlace = tip
        // 点选的那一刻就记住这个点是要给起点的还是终点的。
        pickedForStart = activeIsStart()
        applyPickedPlace()
        mapPickBar.visibility = android.view.View.VISIBLE
    }

    /** 把当前选点写进它该去的那个框。重新点选时也是走这里，输入框才会跟着换。 */
    private fun applyPickedPlace() {
        val tip = pickedPlace ?: return
        fillingText = true
        if (pickedForStart) startPlace = tip else endPlace = tip
        val target = if (pickedForStart) etStart else etEnd
        target.text = tip.name
        // 点完要继续点下一个就还得在同一个框里，焦点留在这儿。
        if (!target.hasFocus()) target.requestFocus()
        fillingText = false
        hideSuggestions()
        tvMapPicked.text = if (pickedForStart) {
            getString(R.string.radar_query_map_picked_as_start, tip.name)
        } else {
            getString(R.string.radar_query_map_picked_as_end, tip.name)
        }
    }

    /** 再点一个点就覆盖上一个，相当于「确认」这一步已经做完了。 */
    private fun confirmMapPick() {
        if (pickedPlace == null) return
        dismissMapPick()
    }

    /** 收起确认条和图钉，但**不**把已经填进输入框的内容撤掉。 */
    private fun dismissMapPick() {
        mapPickBar.visibility = android.view.View.GONE
        queryMap.showPicked(null)
        pickedPlace = null
    }

    private fun cancelMapPick() {
        // 撤掉这次点选：输入框恢复成点选之前的内容，起点终点都算没变。
        dismissMapPick()
    }

    /**
     * 把当前终点交给高德导航，同时把这套规划路线留给雷达预警用。
     * 顺序很重要：先存路线再跳转，否则用户在高德里开走时我们还没记下目的地。
     */
    private fun openInAmap() {
        val end = endPlace
        if (end == null) {
            toast(R.string.radar_query_need_place)
            return
        }
        if (routes.isEmpty()) {
            toast(R.string.radar_query_need_route_first)
            return
        }
        val state = PlannedRouteStore.State(
            endName = end.name,
            endLatitude = end.latitude,
            endLongitude = end.longitude,
            routes = routes,
            selectedRouteId = selectedRouteId ?: routes.first().id,
            savedAtMs = System.currentTimeMillis()
        )
        PlannedRouteStore.save(this, state)
        RadarAlertController.reloadPlannedRoute(this)
        if (!AmapNavigate.openRoutePlan(this, end)) {
            toast(R.string.radar_query_amap_missing)
        }
    }

    override fun onResume() {
        super.onResume()
        queryMap.onHostResume()
        if (resumeLocationUpdates) {
            resumeLocationUpdates = false
            if (hasLocationPermission()) listenForLocation()
        }
    }

    override fun onPause() {
        if (locationListener != null) {
            resumeLocationUpdates = true
            stopLocationUpdates()
        }
        queryMap.onHostPause()
        super.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        queryMap.onHostLowMemory()
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

    /** 收起搜索建议列表，并取消还没回来的那一次搜索。 */
    private fun hideSuggestions() {
        suggestionScroll.visibility = android.view.View.GONE
        mainHandler.removeCallbacks(suggestRunnable)
        placeSuggest.cancel()
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
        hideSuggestions()
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
        // 有路线才给跳转入口：没有路线时跳过去，雷达预警也没东西可依据。
        btnOpenAmap.visibility = android.view.View.VISIBLE
        btnOpenAmap.text = if (AmapNavigate.isInstalled(this)) {
            getString(R.string.radar_query_open_amap)
        } else {
            getString(R.string.radar_query_amap_missing)
        }
        // 已经画过路线的话，只换线不动镜头；第一次出结果才整体铺开并 fit。
        if (shownRouteSet == routes) {
            queryMap.selectRoute(selectedId)
        } else {
            shownRouteSet = routes
            queryMap.show(
                routes = routes,
                selectedId = selectedId,
                start = LatLng(start.latitude, start.longitude),
                end = LatLng(end.latitude, end.longitude)
            )
        }
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

    /**
     * 把状态栏高度垫在顶栏上方。
     * 顶栏高度写死 40dp 是为了让地图更大，但界面画到了状态栏下面，
     * 不加这段位移的话标题会和状态栏的时间、电量叠在一起。
     */
    private fun applyStatusBarInset(view: android.view.View) {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        view.updatePadding(top = view.paddingTop + statusBarHeight)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private companion object {
        /** 短于这个长度的 POI 名字认不出是哪儿（「停车场」「超市」），要补地址。 */
        const val MIN_POI_NAME_LENGTH = 6
    }
}
