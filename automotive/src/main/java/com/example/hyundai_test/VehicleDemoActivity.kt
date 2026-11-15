package com.example.hyundai_test

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.app.Activity
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.TextView

class VehicleDemoActivity : Activity() {
    private lateinit var status: TextView
    private var car: Car? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var vehicle: Vehicle? = null
    private var session = 0
    private var request = 0
    private var attempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (32 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            textSize = 24f
            setText(R.string.pleos_waiting_for_car)
        }
        setContentView(status)
    }

    override fun onStart() {
        super.onStart()
        val currentSession = ++session
        status.setText(R.string.pleos_waiting_for_car)
        car = Car.createCar(this, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connection, ready ->
            if (session != currentSession) return@createCar
            if (!ready) {
                restrictions = null
                restrictRead()
                return@createCar
            }

            val manager = connection.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            restrictions = manager
            manager?.registerListener { refreshRead() }
            refreshRead()
        }
    }

    override fun onStop() {
        ++session
        ++request
        attempted = false
        restrictions?.unregisterListener()
        restrictions = null
        releaseVehicle()
        car?.disconnect()
        car = null
        super.onStop()
    }

    private fun canRead(): Boolean =
        runCatching { restrictions?.currentCarUxRestrictions?.isRequiresDistractionOptimization }
            .getOrNull() == false

    private fun restrictRead() {
        ++request
        attempted = false
        releaseVehicle()
        status.setText(R.string.pleos_unavailable_while_restricted)
    }

    private fun refreshRead() {
        if (!canRead()) {
            restrictRead()
            return
        }
        if (attempted) return
        attempted = true
        if (checkSelfPermission(DOOR_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.pleos_permission_missing)
            Log.w(TAG, "CAR_DOORS is unavailable")
            return
        }
        readDoor(++request)
    }

    private fun readDoor(id: Int) {
        val area = DoorArea(DoorValue.ROW_1_LEFT)
        status.setText(R.string.pleos_checking_door)
        try {
            val client = Vehicle(applicationContext).also { vehicle = it }
            client.initialize()
            val door = client.getDoor()
            door.checkDoorPositionCapabilities(
                onComplete = { config, areas ->
                    runOnUiThread {
                        if (!acceptResult(id)) return@runOnUiThread
                        val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                        if (!readable || area !in areas) {
                            status.setText(R.string.pleos_door_unsupported)
                            Log.w(TAG, "Front-left door position is unavailable")
                            return@runOnUiThread
                        }
                        try {
                            door.getCurrentDoorPosition(
                                area = area,
                                onComplete = { position ->
                                    runOnUiThread {
                                        if (acceptResult(id)) showPosition(position)
                                    }
                                },
                                onFailed = { reportFailure(id, "get", it) },
                            )
                        } catch (error: Exception) {
                            reportFailure(id, "get", error)
                        }
                    }
                },
                onFailed = { reportFailure(id, "capability", it) },
            )
        } catch (error: Exception) {
            reportFailure(id, "initialize/capability", error)
        }
    }

    private fun acceptResult(id: Int): Boolean {
        if (id != request) return false
        if (canRead()) return true
        restrictRead()
        return false
    }

    private fun showPosition(position: Int?) {
        if (position == null) {
            status.setText(R.string.pleos_door_no_value)
            Log.w(TAG, "Door position callback returned no value")
        } else {
            status.text = getString(R.string.pleos_door_position, position)
            Log.i(TAG, "Door position query succeeded for ROW_1_LEFT")
        }
    }

    private fun reportFailure(id: Int, operation: String, error: Exception) = runOnUiThread {
        if (!acceptResult(id)) return@runOnUiThread
        status.setText(R.string.pleos_query_failed)
        Log.w(TAG, "$operation failed: ${error.javaClass.simpleName}")
    }

    private fun releaseVehicle() {
        val client = vehicle ?: return
        vehicle = null
        runCatching { client.release() }.onFailure {
            Log.w(TAG, "Vehicle release failed: ${it.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "PleosVehicleDemo"
        private const val DOOR_PERMISSION = "pleos.car.permission.CAR_DOORS"
    }
}
