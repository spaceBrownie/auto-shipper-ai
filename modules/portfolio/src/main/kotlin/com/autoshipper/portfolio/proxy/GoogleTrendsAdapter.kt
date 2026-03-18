package com.autoshipper.portfolio.proxy

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

@Component
@Profile("!local")
class GoogleTrendsAdapter(
    @Value("\${google-trends.rss.geo:US}") private val geo: String
) : DemandSignalProvider {

    private val logger = LoggerFactory.getLogger(GoogleTrendsAdapter::class.java)

    override fun sourceType(): String = "GOOGLE_TRENDS"

    override fun fetch(): List<RawCandidate> {
        logger.info("Fetching Google Trends RSS feed for geo={}", geo)

        val feedUrl = "https://trends.google.com/trending/rss?geo=$geo"
        val candidates = mutableListOf<RawCandidate>()

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(URI(feedUrl).toURL().openStream())

            val items: NodeList = document.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val item = items.item(i) as Element
                val title = getTextContent(item, "title") ?: continue
                val approxTraffic = getTextContentNS(item, "https://trends.google.com/trending/rss", "approx_traffic")
                    ?: getTextContent(item, "ht:approx_traffic")
                    ?: "200+"
                val pubDate = getTextContent(item, "pubDate") ?: ""

                candidates.add(
                    RawCandidate(
                        productName = title,
                        category = "Trending",
                        description = "Google Trends: $title",
                        sourceType = sourceType(),
                        supplierUnitCost = null,
                        estimatedSellingPrice = null,
                        demandSignals = mapOf(
                            "approx_traffic" to approxTraffic,
                            "trend_date" to pubDate,
                            "geo" to geo
                        )
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch Google Trends RSS: {}", e.message)
        }

        logger.info("Google Trends returned {} candidates", candidates.size)
        return candidates
    }

    private fun getTextContent(parent: Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
    }

    private fun getTextContentNS(parent: Element, namespaceURI: String, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS(namespaceURI, localName)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
    }
}
