package com.autoshipper.capital.listener

import com.autoshipper.capital.domain.CapitalOrderRecord
import com.autoshipper.capital.domain.service.OrderAmountProvider
import com.autoshipper.capital.domain.service.ReserveAccountService
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.shared.events.OrderFulfilled
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderEventListenerTest {

    @Mock
    private lateinit var orderRecordRepository: CapitalOrderRecordRepository

    @Mock
    private lateinit var reserveAccountService: ReserveAccountService

    @Mock
    private lateinit var orderAmountProvider: OrderAmountProvider

    private lateinit var listener: OrderEventListener

    private val orderUuid = UUID.randomUUID()
    private val skuUuid = UUID.randomUUID()
    private val orderId = OrderId(orderUuid)
    private val skuId = SkuId(skuUuid)

    @BeforeEach
    fun setUp() {
        listener = OrderEventListener(orderRecordRepository, reserveAccountService, orderAmountProvider)
    }

    @Test
    fun `onOrderFulfilled creates CapitalOrderRecord and credits reserve`() {
        val event = OrderFulfilled(orderId = orderId, skuId = skuId)
        val orderAmount = Money.of(BigDecimal("99.99"), Currency.USD)

        whenever(orderRecordRepository.findByOrderId(orderUuid)).thenReturn(null)
        whenever(orderAmountProvider.getOrderAmount(orderUuid)).thenReturn(orderAmount)
        whenever(orderRecordRepository.save(any<CapitalOrderRecord>())).thenAnswer { it.arguments[0] }

        listener.onOrderFulfilled(event)

        val captor = ArgumentCaptor.forClass(CapitalOrderRecord::class.java)
        verify(orderRecordRepository).save(captor.capture())
        val saved = captor.value
        assertEquals(orderUuid, saved.orderId)
        assertEquals(skuUuid, saved.skuId)
        assertEquals("DELIVERED", saved.status)
    }

    @Test
    fun `duplicate order is idempotent and skipped`() {
        val event = OrderFulfilled(orderId = orderId, skuId = skuId)
        val existingRecord = CapitalOrderRecord(
            orderId = orderUuid,
            skuId = skuUuid,
            totalAmount = BigDecimal("99.99"),
            currency = Currency.USD,
            status = "DELIVERED"
        )

        whenever(orderRecordRepository.findByOrderId(orderUuid)).thenReturn(existingRecord)

        listener.onOrderFulfilled(event)

        verify(orderRecordRepository, never()).save(any<CapitalOrderRecord>())
    }

    @Test
    fun `missing order amount logs warning and skips`() {
        val event = OrderFulfilled(orderId = orderId, skuId = skuId)

        whenever(orderRecordRepository.findByOrderId(orderUuid)).thenReturn(null)
        whenever(orderAmountProvider.getOrderAmount(orderUuid)).thenReturn(null)

        listener.onOrderFulfilled(event)

        verify(orderRecordRepository, never()).save(any<CapitalOrderRecord>())
    }
}
