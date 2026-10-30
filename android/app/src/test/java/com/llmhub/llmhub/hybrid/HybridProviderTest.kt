package com.llmhub.llmhub.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridProviderTest {
    @Test
    fun officialEndpointsUseHttpsAndCorrectPaths() {
        for (provider in HybridProvider.values()) {
            assertTrue(provider.endpoint.startsWith("https://"))
            assertTrue(provider.endpoint.endsWith("/chat/completions"))
            assertFalse(provider.endpoint.contains("localhost"))
            assertTrue(provider.defaultModel.isNotBlank())
        }
        assertEquals("https://api.openai.com/v1/chat/completions", HybridProvider.OPENAI.endpoint)
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", HybridProvider.NVIDIA.endpoint)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", HybridProvider.OPENROUTER.endpoint)
    }

    @Test
    fun providersHaveUniqueKeyIdentifiers() {
        val ids = HybridProvider.values().map { it.fileId }
        assertEquals(ids.size, ids.toSet().size)
    }
}
