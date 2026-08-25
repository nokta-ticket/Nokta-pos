package com.nokta.pos.comanda.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * `Tab.id` (o Long que trafega por rotas Compose/SavedStateHandle) é
 * `serverId ?: negativeLocalId` — a garantia central que faz isso seguro é
 * que nenhum id gerado por [negativeIdFromLocalId] jamais colide com um id
 * de servidor real (sempre positivo, autoincrement do Postgres).
 */
class NegativeIdFromLocalIdTest {

    @Test
    fun `id derivado e sempre negativo, para qualquer UUID`() {
        repeat(1000) {
            val id = negativeIdFromLocalId(UUID.randomUUID().toString())
            assertTrue("id deveria ser negativo, foi $id", id < 0)
        }
    }

    @Test
    fun `mesmo localId sempre produz o mesmo id negativo`() {
        val localId = UUID.randomUUID().toString()
        val first = negativeIdFromLocalId(localId)
        val second = negativeIdFromLocalId(localId)
        assertEquals(first, second)
    }

    @Test
    fun `localIds diferentes produzem ids diferentes (sem colisao nas amostras testadas)`() {
        val ids = (1..1000).map { negativeIdFromLocalId(UUID.randomUUID().toString()) }
        assertEquals("nenhuma colisão esperada num lote pequeno de UUIDs aleatórios", ids.size, ids.toSet().size)
    }

    @Test
    fun `string que nao e um UUID valido ainda produz um id negativo estavel (fallback)`() {
        val weird = "nao-e-um-uuid"
        val first = negativeIdFromLocalId(weird)
        val second = negativeIdFromLocalId(weird)
        assertTrue(first < 0)
        assertEquals(first, second)
    }

    @Test
    fun `nunca produz zero`() {
        // Long.MIN_VALUE em magnitude and Long.MAX_VALUE nunca é negativo por
        // sofrer overflow — a subtração de 1 no final garante que o pior caso
        // (magnitude = 0) ainda produz -1, nunca 0 (que colidiria com o
        // sentinela "sem comanda ainda" usado em BalcaoViewModel).
        val id = negativeIdFromLocalId("00000000-0000-0000-0000-000000000000")
        assertNotEquals(0L, id)
    }
}
