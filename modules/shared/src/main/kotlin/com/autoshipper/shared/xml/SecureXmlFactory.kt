package com.autoshipper.shared.xml

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OWASP-hardened XML parser factory.
 *
 * All XML parsing in the project must use this factory instead of raw
 * [DocumentBuilderFactory.newInstance] with defaults. Default XML parsers
 * are vulnerable to XXE (XML External Entity) attacks that can exfiltrate
 * files, perform SSRF, or cause denial of service via entity expansion.
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html">OWASP XXE Prevention</a>
 */
object SecureXmlFactory {

    /**
     * Returns a namespace-aware [DocumentBuilder] with external entities
     * and DTDs disabled.
     *
     * Disabled features:
     * - `disallow-doctype-decl` — rejects any document containing a DOCTYPE declaration
     * - `external-general-entities` — prevents resolution of external general entities
     * - `external-parameter-entities` — prevents resolution of external parameter entities
     */
    fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder()
    }
}
