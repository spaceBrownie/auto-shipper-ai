package com.autoshipper.fulfillment.proxy.carrier

import org.junit.jupiter.api.Test

class CjCarrierMapperTest {

    @Test
    fun `USPS maps to USPS`() {
        assert(CjCarrierMapper.normalize("USPS") == "USPS") {
            "Expected 'USPS' but got '${CjCarrierMapper.normalize("USPS")}'"
        }
    }

    @Test
    fun `UPS maps to UPS`() {
        assert(CjCarrierMapper.normalize("UPS") == "UPS") {
            "Expected 'UPS' but got '${CjCarrierMapper.normalize("UPS")}'"
        }
    }

    @Test
    fun `FedEx maps to FedEx`() {
        assert(CjCarrierMapper.normalize("FedEx") == "FedEx") {
            "Expected 'FedEx' but got '${CjCarrierMapper.normalize("FedEx")}'"
        }
    }

    @Test
    fun `fedex lowercase maps to FedEx`() {
        assert(CjCarrierMapper.normalize("fedex") == "FedEx") {
            "Expected 'FedEx' but got '${CjCarrierMapper.normalize("fedex")}'"
        }
    }

    @Test
    fun `DHL maps to DHL`() {
        assert(CjCarrierMapper.normalize("DHL") == "DHL") {
            "Expected 'DHL' but got '${CjCarrierMapper.normalize("DHL")}'"
        }
    }

    @Test
    fun `4PX maps to 4PX`() {
        assert(CjCarrierMapper.normalize("4PX") == "4PX") {
            "Expected '4PX' but got '${CjCarrierMapper.normalize("4PX")}'"
        }
    }

    @Test
    fun `Yanwen maps to Yanwen`() {
        assert(CjCarrierMapper.normalize("Yanwen") == "Yanwen") {
            "Expected 'Yanwen' but got '${CjCarrierMapper.normalize("Yanwen")}'"
        }
    }

    @Test
    fun `YunExpress maps to YunExpress`() {
        assert(CjCarrierMapper.normalize("YunExpress") == "YunExpress") {
            "Expected 'YunExpress' but got '${CjCarrierMapper.normalize("YunExpress")}'"
        }
    }

    @Test
    fun `Cainiao maps to Cainiao`() {
        assert(CjCarrierMapper.normalize("Cainiao") == "Cainiao") {
            "Expected 'Cainiao' but got '${CjCarrierMapper.normalize("Cainiao")}'"
        }
    }

    @Test
    fun `EMS maps to EMS`() {
        assert(CjCarrierMapper.normalize("EMS") == "EMS") {
            "Expected 'EMS' but got '${CjCarrierMapper.normalize("EMS")}'"
        }
    }

    @Test
    fun `unknown carrier passes through unchanged`() {
        assert(CjCarrierMapper.normalize("SomeObscureCarrier") == "SomeObscureCarrier") {
            "Expected 'SomeObscureCarrier' but got '${CjCarrierMapper.normalize("SomeObscureCarrier")}'"
        }
    }

    @Test
    fun `case insensitivity - all uppercase UPS`() {
        assert(CjCarrierMapper.normalize("ups") == "UPS") {
            "Expected 'UPS' but got '${CjCarrierMapper.normalize("ups")}'"
        }
    }

    @Test
    fun `case insensitivity - mixed case usps`() {
        assert(CjCarrierMapper.normalize("Usps") == "USPS") {
            "Expected 'USPS' but got '${CjCarrierMapper.normalize("Usps")}'"
        }
    }

    @Test
    fun `empty string passes through unchanged`() {
        assert(CjCarrierMapper.normalize("") == "") {
            "Expected empty string but got '${CjCarrierMapper.normalize("")}'"
        }
    }
}
