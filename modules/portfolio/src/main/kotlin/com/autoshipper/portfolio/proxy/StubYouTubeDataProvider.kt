package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local")
class StubYouTubeDataProvider : DemandSignalProvider {

    override fun sourceType(): String = "YOUTUBE_DATA"

    override fun fetch(): List<RawCandidate> = listOf(
        RawCandidate(
            productName = "Portable Bluetooth Speaker Review",
            category = "Product Review",
            description = "YouTube video reviewing portable Bluetooth speakers",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf(
                "video_id" to "yt_stub_001",
                "view_count" to "245000",
                "like_count" to "8700",
                "comment_count" to "1230",
                "channel_subscriber_count" to "520000",
                "publish_date" to "2026-02-15",
                "search_term" to "best product review"
            )
        ),
        RawCandidate(
            productName = "Best Kitchen Scale 2026",
            category = "Product Review",
            description = "YouTube video comparing top kitchen scales",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf(
                "video_id" to "yt_stub_002",
                "view_count" to "132000",
                "like_count" to "4500",
                "comment_count" to "890",
                "channel_subscriber_count" to "310000",
                "publish_date" to "2026-03-01",
                "search_term" to "must have kitchen gadgets"
            )
        ),
        RawCandidate(
            productName = "Top 5 Wireless Earbuds",
            category = "Product Review",
            description = "YouTube video ranking wireless earbuds",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf(
                "video_id" to "yt_stub_003",
                "view_count" to "578000",
                "like_count" to "21000",
                "comment_count" to "3400",
                "channel_subscriber_count" to "1200000",
                "publish_date" to "2026-03-10",
                "search_term" to "top gadgets"
            )
        )
    )
}
