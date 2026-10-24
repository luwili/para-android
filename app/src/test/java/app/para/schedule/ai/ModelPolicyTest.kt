package app.para.schedule.ai

import org.junit.Assert.*
import org.junit.Test

class ModelPolicyTest {
    private fun phone(ram: Long, free: Long, disk: Long = 20 * GIB) = DeviceProfile(ram, free, disk, true, false, 8)
    @Test fun `4GB phone gets small model`() { assertEquals(ModelCatalog.models[0], ModelPolicy.recommend(phone(4 * GIB, 2 * GIB))) }
    @Test fun `8GB with limited available RAM gets balanced model`() { assertEquals(ModelCatalog.models[1], ModelPolicy.recommend(phone(8 * GIB, 3 * GIB))) }
    @Test fun `large phone can use large model`() { assertEquals(ModelCatalog.models[2], ModelPolicy.recommend(phone(12 * GIB, 6 * GIB))) }
    @Test fun `manual choice is not restricted to automatic RAM tier`() {
        val phone = phone(6 * GIB, 5 * GIB)
        assertEquals(ModelCatalog.models[1], ModelPolicy.recommend(phone))
        assertTrue(ModelPolicy.canRun(ModelCatalog.models[2], phone))
    }
    @Test fun `32bit low RAM and memory pressure do not run model`() {
        val good = phone(12 * GIB, 6 * GIB)
        assertNull(ModelPolicy.recommend(good.copy(supports64Bit = false)))
        assertNull(ModelPolicy.recommend(good.copy(lowRam = true)))
        assertNull(ModelPolicy.recommend(good.copy(availableRam = 200 * MIB)))
    }
    @Test fun `disk budget includes staging and private copy but installed files need no new download`() {
        val device = phone(4 * GIB, 2 * GIB, 200 * MIB)
        assertNull(ModelPolicy.recommend(device))
        assertEquals(ModelCatalog.models.first(), ModelPolicy.recommend(device, setOf(ModelCatalog.models.first().id)))
        assertTrue(ModelPolicy.storageNeeded(ModelCatalog.models.first()) > ModelCatalog.models.first().bytes * 2)
    }
    @Test fun `catalog URLs revisions sizes and hashes are pinned`() {
        ModelCatalog.models.forEach {
            assertTrue(it.url.startsWith("https://"))
            assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(it.url))
            assertTrue(Regex("[0-9a-f]{64}").matches(it.sha256))
            assertTrue(it.bytes > 100 * MIB)
        }
    }
}
