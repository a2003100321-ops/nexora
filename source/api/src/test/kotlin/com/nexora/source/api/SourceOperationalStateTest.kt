package com.nexora.source.api

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceOperationalStateTest {
    @Test
    fun searchCapabilityUserChoiceAndAvailabilityAreIndependent() {
        val state = SourceOperationalState(
            searchCapability = SourceSearchCapability.UNSUPPORTED,
            userActivation = SourceUserActivation.DISABLED,
            availability = SourceAvailability.TEMPORARILY_UNAVAILABLE,
        )

        assertEquals(SourceSearchCapability.UNSUPPORTED, state.searchCapability)
        assertEquals(SourceUserActivation.DISABLED, state.userActivation)
        assertEquals(SourceAvailability.TEMPORARILY_UNAVAILABLE, state.availability)
    }

    @Test
    fun loadFailureDoesNotRewriteSearchSupport() {
        val initial = SourceOperationalState(
            searchCapability = SourceSearchCapability.SUPPORTED,
        )

        val failed = initial.copy(availability = SourceAvailability.LOAD_FAILED)

        assertEquals(SourceSearchCapability.SUPPORTED, failed.searchCapability)
        assertEquals(SourceUserActivation.ENABLED, failed.userActivation)
        assertEquals(SourceAvailability.LOAD_FAILED, failed.availability)
    }
}
