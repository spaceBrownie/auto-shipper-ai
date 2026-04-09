package com.autoshipper.fulfillment.proxy.carrier

object CjCarrierMapper {

    private val MAPPING: Map<String, String> = mapOf(
        "usps" to "USPS",
        "ups" to "UPS",
        "fedex" to "FedEx",
        "dhl" to "DHL",
        "4px" to "4PX",
        "yanwen" to "Yanwen",
        "yunexpress" to "YunExpress",
        "cainiao" to "Cainiao",
        "ems" to "EMS"
    )

    fun normalize(cjLogisticName: String): String {
        return MAPPING[cjLogisticName.lowercase()] ?: cjLogisticName
    }
}
