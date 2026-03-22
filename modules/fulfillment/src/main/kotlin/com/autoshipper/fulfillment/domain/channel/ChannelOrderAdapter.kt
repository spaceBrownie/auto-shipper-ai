package com.autoshipper.fulfillment.domain.channel

/**
 * Abstraction for parsing channel-specific order payloads into a normalized ChannelOrder model.
 * Each sales channel (Shopify, Amazon, eBay, etc.) implements this interface.
 */
interface ChannelOrderAdapter {
    fun parse(rawPayload: String): ChannelOrder
    fun channelName(): String
}
