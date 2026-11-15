package com.example.hyundai_test

import android.car.VehicleGear

internal class GleozReturnTrigger {
    private var gear: Int? = null
    private var pending = false

    val parked: Boolean get() = gear == VehicleGear.GEAR_PARK

    fun onGear(next: Int?) {
        if (next != VehicleGear.GEAR_PARK) {
            pending = false
        } else if (gear == VehicleGear.GEAR_DRIVE) {
            pending = true
        }
        gear = next
    }

    fun takeIfAllowed(allowed: Boolean): Boolean {
        if (!allowed || !pending) return false
        pending = false
        return true
    }

    fun discardPending() {
        pending = false
    }
}
