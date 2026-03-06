package com.autoshipper.catalog.proxy.carrier

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.math.BigDecimal

@Configuration
@Profile("local")
class StubCarrierRateConfiguration {

    @Bean
    fun stubFedExRateProvider(): CarrierRateProvider = StubCarrierRateProvider("FedEx", BigDecimal("7.99"))

    @Bean
    fun stubUpsRateProvider(): CarrierRateProvider = StubCarrierRateProvider("UPS", BigDecimal("8.49"))

    @Bean
    fun stubUspsRateProvider(): CarrierRateProvider = StubCarrierRateProvider("USPS", BigDecimal("5.99"))
}

class StubCarrierRateProvider(
    override val carrierName: String,
    private val fixedRate: BigDecimal
) : CarrierRateProvider {
    override fun getRate(origin: Address, destination: Address, dims: PackageDimensions): Money =
        Money.of(fixedRate, Currency.USD)
}
