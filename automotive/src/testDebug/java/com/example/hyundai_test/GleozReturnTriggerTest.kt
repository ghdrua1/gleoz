package com.example.hyundai_test

import android.car.VehicleGear.GEAR_DRIVE
import android.car.VehicleGear.GEAR_NEUTRAL
import android.car.VehicleGear.GEAR_PARK
import android.car.VehicleGear.GEAR_REVERSE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GleozReturnTriggerTest {
    @Test
    fun waitsForRestrictionToClearAndConsumesOnceAcrossDuplicateGearEvents() {
        val trigger = GleozReturnTrigger()
        trigger.onGear(GEAR_PARK)
        assertFalse(trigger.takeIfAllowed(true))

        trigger.onGear(GEAR_DRIVE)
        trigger.onGear(GEAR_DRIVE)
        trigger.onGear(GEAR_PARK)
        assertFalse(trigger.takeIfAllowed(false))
        trigger.onGear(GEAR_PARK)
        assertTrue(trigger.takeIfAllowed(true))
        trigger.onGear(GEAR_PARK)
        assertFalse(trigger.takeIfAllowed(true))
    }

    @Test
    fun onlyDirectDriveToParkWithKnownGearCanRequestReturn() {
        for (interveningGear in listOf(GEAR_REVERSE, GEAR_NEUTRAL, null)) {
            val trigger = GleozReturnTrigger()
            trigger.onGear(GEAR_DRIVE)
            trigger.onGear(interveningGear)
            trigger.onGear(GEAR_PARK)
            assertFalse(trigger.takeIfAllowed(true))
        }
    }

    @Test
    fun leavingParkOrDiscardingClearsUnplayedReturn() {
        val trigger = GleozReturnTrigger()
        trigger.onGear(GEAR_DRIVE)
        trigger.onGear(GEAR_PARK)
        trigger.onGear(GEAR_REVERSE)
        assertFalse(trigger.parked)
        assertFalse(trigger.takeIfAllowed(true))

        trigger.onGear(GEAR_DRIVE)
        trigger.onGear(GEAR_PARK)
        trigger.discardPending()
        trigger.onGear(GEAR_PARK)
        assertFalse(trigger.takeIfAllowed(true))
    }
}
