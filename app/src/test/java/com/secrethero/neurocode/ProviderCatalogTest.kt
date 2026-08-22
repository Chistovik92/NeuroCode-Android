package com.secrethero.neurocode

import com.secrethero.neurocode.ai.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test
    fun providerIdsAreUniqueAndEndpointsUseHttps() {
        val providers = ProviderCatalog.defaults()
        assertTrue(providers.isNotEmpty())
        assertEquals(providers.size, providers.map { it.id }.distinct().size)
        assertTrue(providers.all { it.baseUrl.startsWith("https://") })
        assertTrue(providers.all { it.model.isNotBlank() })
    }
}
