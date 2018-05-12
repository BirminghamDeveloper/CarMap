package com.example.trongtuyen.carmap

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent.getActivity
import android.app.ProgressDialog.show
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.support.design.widget.NavigationView
import android.support.v4.app.ActivityCompat
import android.support.v4.view.GravityCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.PopupWindow
import android.widget.Toast
import com.example.trongtuyen.carmap.activity.common.SignInActivity
import com.example.trongtuyen.carmap.adapters.CustomInfoWindowAdapter
import com.example.trongtuyen.carmap.controllers.AppController
import com.example.trongtuyen.carmap.models.Geometry
import com.example.trongtuyen.carmap.models.User
import com.example.trongtuyen.carmap.services.APIServiceGenerator
import com.example.trongtuyen.carmap.services.ErrorUtils
import com.example.trongtuyen.carmap.services.UserService
import com.example.trongtuyen.carmap.services.models.UserProfileResponse
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.*
import com.google.android.gms.location.places.Place
import com.google.android.gms.location.places.ui.PlaceAutocompleteFragment
import com.google.android.gms.location.places.ui.PlaceSelectionListener
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.app_bar_main.*
import kotlinx.android.synthetic.main.nav_header_main.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, OnMapReadyCallback, GoogleMap.OnMarkerClickListener, GoogleMap.OnInfoWindowClickListener, GoogleMap.OnInfoWindowCloseListener {

    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var lastLocation: Location
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest
    private var locationUpdateState = false
    val mPolylineOptions = PolylineOptions()
    private var mapReady = false
    private var mapSetup = false
    private var locationUpdateRunning = false
    private var alreadyAskPermission = false
    private var resumeFromRequestPermissionFail = false

    // PlaceAutoCompleteFragment
    private var placeAutoComplete: PlaceAutocompleteFragment? = null

    // Maerket options for set up marker
    private var markerOptions = MarkerOptions()

    // Popup windows
    private var mPopupWindow : PopupWindow? = null

    // List of user of other cars
    lateinit var listUser : List<User>

    companion object {
        private const val CODE_REQUEST_PERMISSION_FOR_UPDATE_LOCATION = 1
        private const val CODE_REQUEST_SETTING_FOR_UPDATE_LOCATION = 2
        private const val CODE_REQUEST_PERMISSION_FOR_SETUP_MAP = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Obtain placeAutoComplete fragment
        placeAutoComplete = fragmentManager.findFragmentById(R.id.place_autocomplete) as PlaceAutocompleteFragment
        placeAutoComplete!!.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {

                Log.d("Maps", "Place selected: " + place.name)
                addMarker(place);
            }

            override fun onError(status: Status) {
                Log.d("Maps", "An error occurred: $status")
            }
        })

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
                .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        val toggle = ActionBarDrawerToggle(
                this, drawer_layout, null, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer_layout.addDrawerListener(toggle)
        toggle.syncState()

        nav_view.setNavigationItemSelectedListener(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                super.onLocationResult(p0)

                lastLocation = p0.lastLocation
                moveMarker(markerOptions, LatLng(lastLocation.latitude, lastLocation.longitude))
                mPolylineOptions.add(LatLng(lastLocation.latitude, lastLocation.longitude))
                if (mapReady) {
                    map.addPolyline(mPolylineOptions)
                }
            }
        }
        createLocationRequest()

        // Load user profile
        loadUserProfile()
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Try catch parsing custom style json file to map
//        try {
//            // Customise the styling of the base map using a JSON object defined
//            // in a raw resource file: style_json.
//            var success = googleMap.setMapStyle(
//                    MapStyleOptions.loadRawResourceStyle(
//                            this, R.raw.style_json))
//
//            if (!success) {
//                Log.e("Resources", "Style parsing failed.")
//            }
//        } catch (e: Resources.NotFoundException) {
//            Log.e("Resources", "Can't find style. Error: ", e)
//        }

        mapReady = true

        //map.uiSettings.isZoomControlsEnabled = true

        //Set Custom InfoWindow Adapter
        var adapter  = CustomInfoWindowAdapter(this)
        map.setInfoWindowAdapter(adapter);

        map.setOnMarkerClickListener(this)
        map.setOnInfoWindowClickListener(this)
        map.setOnInfoWindowCloseListener(this)


        if (!mapSetup) {
            setUpMapWrapper()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setUpMap() {

        map.isMyLocationEnabled = true

        fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
            // Got last known location. In some rare situations this can be null.
            if (location != null) {
                lastLocation = location
                val currentLatLng = LatLng(location.latitude, location.longitude)

                // Add Marker
                markerOptions.position(currentLatLng)
                markerOptions.title("Current location")
                markerOptions.icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_car))
                map.addMarker(markerOptions)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 13f))

                mPolylineOptions.add(currentLatLng)
            }
        }

        mapSetup = true
    }

    private fun setUpMapWrapper() {
        if (!mapReady) return
        if (ActivityCompat.checkSelfPermission(this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!alreadyAskPermission){
                ActivityCompat.requestPermissions(this,
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), CODE_REQUEST_PERMISSION_FOR_SETUP_MAP)
                alreadyAskPermission = true
            }
            return
        }
        setUpMap()
    }

    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        when (item.itemId) {
            R.id.action_settings -> return true
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.nav_camera -> {
                onGetAllUser()
            }
            R.id.nav_gallery -> {
                if (::lastLocation.isInitialized) {
                    val listGeo: List<Double> = listOf(lastLocation.longitude, lastLocation.latitude)

                    val newGeo = Geometry("Point", listGeo)
                    Log.e("LOC", lastLocation.longitude.toString())
                    Log.e("LOC", lastLocation.latitude.toString())
                    val user = User("", "", "", "", "", "", newGeo)
                    onUpdateHomeLocation(user)
                }
            }
            R.id.nav_slideshow -> {

            }
            R.id.nav_manage -> {

            }
            R.id.nav_share -> {

            }
            R.id.nav_signout -> {
                onSignOut()
            }
        }

        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun openAppSettings() {
        val intent = Intent()
        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    fun addMarker(p: Place) {
        val markerOptions = MarkerOptions()
        markerOptions.position(p.latLng)
        markerOptions.title(p.name.toString() + "")
        markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))

        map.addMarker(markerOptions)
        map.moveCamera(CameraUpdateFactory.newLatLng(p.latLng))
        map.animateCamera(CameraUpdateFactory.zoomTo(13f))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null /* Looper */)
        locationUpdateRunning = true
    }

    private fun startLocationUpdatesWrapper() {
        if (!mapSetup || locationUpdateRunning) return
        if (ActivityCompat.checkSelfPermission(this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!alreadyAskPermission) {
                ActivityCompat.requestPermissions(this,
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), CODE_REQUEST_PERMISSION_FOR_UPDATE_LOCATION)
                alreadyAskPermission = true
            }
            return
        }
        startLocationUpdates()
    }

    @SuppressLint("RestrictedApi")
    private fun createLocationRequest() {
        // 1
        locationRequest = LocationRequest()
        // 2
        locationRequest.interval = 10000
        // 3
        locationRequest.fastestInterval = 5000
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        val builder = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)

        // 4
        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        // 5
        task.addOnSuccessListener {
            locationUpdateState = true
            //startLocationUpdatesWrapper()
        }
        task.addOnFailureListener { e ->
            // 6
            if (e is ResolvableApiException) {
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    e.startResolutionForResult(this@MainActivity,
                            CODE_REQUEST_SETTING_FOR_UPDATE_LOCATION)
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        //super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CODE_REQUEST_SETTING_FOR_UPDATE_LOCATION) {
            if (resultCode == Activity.RESULT_OK) {
                locationUpdateState = true
                //startLocationUpdatesWrapper()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        //Toast.makeText(this,"On Pause",Toast.LENGTH_SHORT).show()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdateRunning = false
    }

    public override fun onStop() {
        super.onStop()
        //Toast.makeText(this,"On Stop",Toast.LENGTH_SHORT).show()
    }

    public override fun onResume() {
        super.onResume()
        //Toast.makeText(this,"On Resume",Toast.LENGTH_SHORT).show()
        if (!mapSetup && !resumeFromRequestPermissionFail) {
            setUpMapWrapper()
        }
        if (mapSetup && locationUpdateState && !locationUpdateRunning&& !resumeFromRequestPermissionFail) {
            startLocationUpdatesWrapper()
        }
        resumeFromRequestPermissionFail=false
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            CODE_REQUEST_PERMISSION_FOR_UPDATE_LOCATION -> {
                alreadyAskPermission = false
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {

                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    startLocationUpdates()
                } else {

                    //Toast.makeText(this, "UPDATE LOCATION DENIED", Toast.LENGTH_LONG).show()
                    //Log.e("K", "UPDATE LOCATION DENIED")
                    resumeFromRequestPermissionFail = true;
                    openAppSettings()

                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return
            }

            CODE_REQUEST_PERMISSION_FOR_SETUP_MAP -> {
                alreadyAskPermission = false
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {

                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    setUpMap()
                } else {
                    //Toast.makeText(this, "SETUP MAP DENIED", Toast.LENGTH_LONG).show()
                    //Log.e("K", "SETUP MAP DENIED")
                    resumeFromRequestPermissionFail = true;
                    openAppSettings()


                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return
            }

        // Add other 'when' lines to check for other
        // permissions this app might request.

            else -> {
                // Ignore all other requests.
            }
        }
    }

    // ======== ON NAVIGATION BUTTON EVENT ==================================
    private fun loadUserProfile() {
        if (AppController.accessToken != null && AppController.accessToken.toString().length > 0) {
            val service = APIServiceGenerator.createService(UserService::class.java)
            val call = service.userProfile
            call.enqueue(object : Callback<UserProfileResponse> {
                override fun onResponse(call: Call<UserProfileResponse>, response: Response<UserProfileResponse>) {
                    if (response.isSuccessful()) {
                        AppController.userProfile = response.body().user
                        updateInformation()
                    } else {
                        val apiError = ErrorUtils.parseError(response)
                        Toast.makeText(this@MainActivity, apiError.message(), Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<UserProfileResponse>, t: Throwable) {

                }
            })
        }
    }

    private fun updateInformation() {
        val user = AppController.userProfile
        tvName.text = user?.name
        tvEmail.text = user?.email
//        Helper.loadAvatarWithoutPlaceHolder(getActivity(), avatar, user.getAvatar(), net.diadiemmuasam.user.R.drawable.default_avatar)
    }

    private fun onSignOut() {
        AppController.signOut()
        Toast.makeText(this, "Đăng xuất thành công!", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, SignInActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        this.finish()
    }
    // ======================================================================

    // ======== MARKER CLICK GROUP ==========================================
    override fun onMarkerClick(p0: Marker): Boolean {
        p0.showInfoWindow()
        return false
    }

    // Sự kiện khi click vào info windows
    override fun onInfoWindowClick(p0: Marker) {
        val inflater = this.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val customViewPopup = inflater.inflate(R.layout.custom_popup_layout,null)
        mPopupWindow = PopupWindow(customViewPopup, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        mPopupWindow!!.showAtLocation(this.currentFocus, Gravity.BOTTOM,0,0)

        // Phải có con trỏ vào customViewPopup, nếu không sẽ null
        val btnHello = customViewPopup.findViewById<Button>(R.id.btnHello)
        btnHello.setOnClickListener { Toast.makeText(this, "Helloooooooooooo", Toast.LENGTH_SHORT).show() }
    }

    override fun onInfoWindowClose(p0: Marker?) {
        // Đóng popup windows
        mPopupWindow!!.dismiss()
    }

    private fun addUserMarker(user: User){
        val markerOptions = MarkerOptions()
        // LatLag điền theo thứ tự latitude, longitude
        // Còn ở server Geo là theo thứ tự longitude, latitude
        // Random
        val random = Random()
        markerOptions.position(LatLng(user.homeLocation!!.coordinates!![1], user.homeLocation!!.coordinates!![0]))
        markerOptions.title(user.name)
        markerOptions.snippet(user.email)
        markerOptions.icon(BitmapDescriptorFactory.defaultMarker(random.nextFloat()*360))
        map.addMarker(markerOptions)
    }

    fun moveMarker(marker: MarkerOptions, latLng: LatLng) {
        marker.position(latLng)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 13f))
    }
    // ========================================================================

    // ======== API CALL AND LISTENERS ========================================
    internal fun onGetAllUser(){
        val service = APIServiceGenerator.createService(UserService::class.java)
        val call = service.allUserProfile
        call.enqueue(object : Callback<List<User>> {
            override fun onResponse(call: Call<List<User>>, response: Response<List<User>>) {
                if (response.isSuccessful) {
                    onAllUserProfileSuccess(response.body())
                } else {
                    val apiError = ErrorUtils.parseError(response)
                    Toast.makeText(this@MainActivity, "Lỗi: " + apiError.message(), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<User>>, t: Throwable) {
                Toast.makeText(this@MainActivity, "Không có kết nối Internet", Toast.LENGTH_SHORT).show()
                t.printStackTrace()
            }
        })
    }

    private fun onAllUserProfileSuccess(response: List<User>) {
        listUser = response
        drawOtherUsers()
    }

    private fun drawOtherUsers(){
        for (i in 0 until listUser.size){
            // Except current user
            if (listUser[i].email != AppController.userProfile!!.email)
                addUserMarker(listUser[i])
        }
    }

    internal fun onUpdateHomeLocation(user : User){
        val service = APIServiceGenerator.createService(UserService::class.java)
        val call = service.updateHomeLocation(user)
        call.enqueue(object : Callback<UserProfileResponse> {
            override fun onResponse(call: Call<UserProfileResponse>, response: Response<UserProfileResponse>) {
                if (response.isSuccessful()) {
                    Toast.makeText(this@MainActivity, "Vị trí mới: " + "long:" +response.body().user?.homeLocation?.coordinates!![0] + "- lat: " + response.body().user?.homeLocation?.coordinates!![1], Toast.LENGTH_SHORT).show()
                } else {
                    val apiError = ErrorUtils.parseError(response)
                    Toast.makeText(this@MainActivity, "Lỗi: " + apiError.message(), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<UserProfileResponse>, t: Throwable) {
                Log.e("Failure", "Error: " + t.message)
            }
        })
    }
    // =====================================================================
}
