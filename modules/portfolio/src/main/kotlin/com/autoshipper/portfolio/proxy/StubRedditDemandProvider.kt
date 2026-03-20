package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local")
class StubRedditDemandProvider : DemandSignalProvider {

    override fun sourceType(): String = "REDDIT"

    override fun fetch(): List<RawCandidate> = listOf(
        RawCandidate(
            productName = "This cast iron skillet changed my cooking",
            category = "BuyItForLife",
            description = "Reddit post discussing a high-quality cast iron skillet",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf(
                "post_id" to "reddit_stub_001",
                "subreddit" to "BuyItForLife",
                "upvote_count" to "4200",
                "comment_count" to "387",
                "post_age_hours" to "18",
                "subreddit_subscribers" to "1850000"
            )
        ),
        RawCandidate(
            productName = "Finally found a good USB-C hub",
            category = "gadgets",
            description = "Reddit post recommending a reliable USB-C hub",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf(
                "post_id" to "reddit_stub_002",
                "subreddit" to "gadgets",
                "upvote_count" to "2800",
                "comment_count" to "215",
                "post_age_hours" to "6",
                "subreddit_subscribers" to "22400000"
            )
        ),
        RawCandidate(
            productName = "Recommend: ergonomic keyboard for WFH",
            category = "homeautomation",
            description = "Reddit post about ergonomic keyboards for home office",
            sourceType = sourceType(),
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf(
                "post_id" to "reddit_stub_003",
                "subreddit" to "homeautomation",
                "upvote_count" to "1500",
                "comment_count" to "142",
                "post_age_hours" to "32",
                "subreddit_subscribers" to "2100000"
            )
        )
    )
}
