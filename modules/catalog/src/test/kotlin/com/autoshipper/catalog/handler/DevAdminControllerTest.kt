package com.autoshipper.catalog.handler

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.service.SkuService
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Base64
import java.util.UUID

/**
 * Unit tests for the FR-030 / RAT-53 gated DevAdminController.
 *
 * Four independently-gated configurations:
 *  - Bean absent (dev-listing-enabled=false)  -> T-32 via DevAdminControllerDisabledTest
 *  - Bean present + blank token                -> T-33 via DevAdminControllerBlankTokenTest
 *  - Bean present + token "secret"             -> T-34..T-41 via DevAdminControllerEnabledTest
 *
 * Implementation note on SkuService: we cannot use @MockBean on a Kotlin class
 * whose methods take value-class arguments (SkuId → unboxed UUID at the JVM level).
 * Mockito matchers return null for value classes and Kotlin's generated unbox
 * bridges immediately NPE. Instead, we build a Mockito mock with a single
 * Answer that records every invocation and returns sensible defaults. Verification
 * reads the invocation log directly.
 */
class DevAdminControllerTest {

    /**
     * A Mockito Answer that records every call and dispatches by method name.
     * `findById`  → returns a fresh Sku in the configured state.
     * `transition` → records (SkuId, SkuState) and returns a fresh Sku in that state.
     * Any other method → null (never invoked by DevAdminController).
     */
    class RecordingSkuServiceAnswer : Answer<Any?> {
        val transitionCalls: MutableList<Pair<SkuId, SkuState>> = mutableListOf()
        var currentState: SkuState = SkuState.StressTesting

        override fun answer(invocation: InvocationOnMock): Any? {
            // Kotlin compiles methods taking value-class params with a mangled suffix
            // (e.g. `findById-KPkNwOk`). Match by prefix rather than exact name.
            val name = invocation.method.name
            return when {
                name == "findById" || name.startsWith("findById-") -> {
                    val arg0 = invocation.arguments[0]
                    // Kotlin value class SkuId is unboxed to UUID at JVM level.
                    val uuid = when (arg0) {
                        is UUID -> arg0
                        is SkuId -> arg0.value
                        else -> error("Unexpected findById arg type: ${arg0?.javaClass}")
                    }
                    Sku(id = uuid, name = "fixture", category = "test")
                        .also { it.applyTransition(currentState) }
                }
                name == "transition" || name.startsWith("transition-") -> {
                    val arg0 = invocation.arguments[0]
                    val uuid = when (arg0) {
                        is UUID -> arg0
                        is SkuId -> arg0.value
                        else -> error("Unexpected transition arg0 type: ${arg0?.javaClass}")
                    }
                    val newState = invocation.arguments[1] as SkuState
                    transitionCalls.add(SkuId(uuid) to newState)
                    currentState = newState
                    Sku(id = uuid, name = "fixture", category = "test")
                        .also { it.applyTransition(newState) }
                }
                else -> null
            }
        }
    }

    /**
     * Minimal test-only boot app. Excludes JPA + DataSource autoconfig because we
     * replace SkuService entirely via @TestConfiguration.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
        exclude = [
            DataSourceAutoConfiguration::class,
            DataSourceTransactionManagerAutoConfiguration::class,
            HibernateJpaAutoConfiguration::class,
            JpaRepositoriesAutoConfiguration::class,
        ],
    )
    @ComponentScan(
        basePackageClasses = [DevAdminController::class],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [DevAdminController::class],
            ),
        ],
    )
    class TestApp {
        @Bean
        fun recordingAnswer(): RecordingSkuServiceAnswer = RecordingSkuServiceAnswer()

        @Bean
        @Primary
        fun skuService(answer: RecordingSkuServiceAnswer): SkuService =
            Mockito.mock(SkuService::class.java, answer)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-32 — bean absent (@ConditionalOnProperty=false) → 404
    // ─────────────────────────────────────────────────────────────────────────
    @SpringBootTest(classes = [TestApp::class])
    @AutoConfigureMockMvc
    @TestPropertySource(
        properties = [
            "autoshipper.admin.dev-listing-enabled=false",
            "autoshipper.admin.dev-token=secret",
        ],
    )
    class DevAdminControllerDisabledTest {

        @Autowired private lateinit var mockMvc: MockMvc

        @Autowired private lateinit var answer: RecordingSkuServiceAnswer

        @BeforeEach
        fun clear() { answer.transitionCalls.clear() }

        @Test
        fun `T-32 dev-listing disabled returns 404 (bean absent)`() {
            val id = UUID.randomUUID()
            mockMvc.perform(post("/admin/dev/sku/$id/list"))
                .andExpect(status().isNotFound)
            assert(answer.transitionCalls.isEmpty()) {
                "skuService.transition must NOT be called; was ${answer.transitionCalls}"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-33 — bean present but token is blank → 401 (defense-in-depth)
    // ─────────────────────────────────────────────────────────────────────────
    @SpringBootTest(classes = [TestApp::class])
    @AutoConfigureMockMvc
    @TestPropertySource(
        properties = [
            "autoshipper.admin.dev-listing-enabled=true",
            "autoshipper.admin.dev-token=",
        ],
    )
    class DevAdminControllerBlankTokenTest {

        @Autowired private lateinit var mockMvc: MockMvc

        @Autowired private lateinit var answer: RecordingSkuServiceAnswer

        @BeforeEach
        fun clear() { answer.transitionCalls.clear() }

        @Test
        fun `T-33 blank token rejects all requests with 401`() {
            val id = UUID.randomUUID()
            val basic = "Basic " + Base64.getEncoder()
                .encodeToString("admin:anything".toByteArray(Charsets.UTF_8))

            mockMvc.perform(
                post("/admin/dev/sku/$id/list").header("Authorization", basic),
            ).andExpect(status().isUnauthorized)

            mockMvc.perform(post("/admin/dev/sku/$id/list"))
                .andExpect(status().isUnauthorized)

            assert(answer.transitionCalls.isEmpty()) {
                "skuService.transition must NOT be called; was ${answer.transitionCalls}"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-34..T-41 — bean present + token="secret"
    // ─────────────────────────────────────────────────────────────────────────
    @SpringBootTest(classes = [TestApp::class])
    @AutoConfigureMockMvc
    @TestPropertySource(
        properties = [
            "autoshipper.admin.dev-listing-enabled=true",
            "autoshipper.admin.dev-token=secret",
        ],
    )
    class DevAdminControllerEnabledTest {

        @Autowired private lateinit var mockMvc: MockMvc

        @Autowired private lateinit var answer: RecordingSkuServiceAnswer

        @BeforeEach
        fun resetAnswer() {
            answer.transitionCalls.clear()
            answer.currentState = SkuState.StressTesting
        }

        private fun basicHeader(user: String, pass: String): String =
            "Basic " + Base64.getEncoder()
                .encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

        @Test
        fun `T-34 no Authorization header returns 401`() {
            val id = UUID.randomUUID()
            mockMvc.perform(post("/admin/dev/sku/$id/list"))
                .andExpect(status().isUnauthorized)
            assert(answer.transitionCalls.isEmpty())
        }

        @Test
        fun `T-35 wrong Basic credentials returns 401`() {
            val id = UUID.randomUUID()
            mockMvc.perform(
                post("/admin/dev/sku/$id/list")
                    .header("Authorization", basicHeader("admin", "wrong-token")),
            ).andExpect(status().isUnauthorized)
            assert(answer.transitionCalls.isEmpty())
        }

        @Test
        fun `T-36 correct token returns 202 and body, calls SkuService transition once`() {
            val id = UUID.randomUUID()

            mockMvc.perform(
                post("/admin/dev/sku/$id/list")
                    .header("Authorization", basicHeader("admin", "secret")),
            )
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.skuId").value(id.toString()))
                .andExpect(jsonPath("$.state").value("LISTED"))

            assert(answer.transitionCalls.size == 1) {
                "transition() should have been called once, was ${answer.transitionCalls.size}"
            }
            val (calledId, calledState) = answer.transitionCalls[0]
            assert(calledId == SkuId(id)) { "transition arg[0] should be SkuId($id), was $calledId" }
            assert(calledState === SkuState.Listed) {
                "transition arg[1] should be SkuState.Listed, was $calledState"
            }
        }

        @Test
        fun `T-37 malformed base64 in Authorization returns 401 not 500`() {
            val id = UUID.randomUUID()
            mockMvc.perform(
                post("/admin/dev/sku/$id/list")
                    .header("Authorization", "Basic !!!not-base64!!!"),
            ).andExpect(status().isUnauthorized)
            assert(answer.transitionCalls.isEmpty())
        }

        @Test
        fun `T-38 invalid UUID path variable returns 400`() {
            mockMvc.perform(
                post("/admin/dev/sku/not-a-uuid/list")
                    .header("Authorization", basicHeader("admin", "secret")),
            ).andExpect(status().isBadRequest)
        }

        @Test
        fun `T-39 already-Listed SKU short-circuits idempotently (no transition call)`() {
            val id = UUID.randomUUID()
            answer.currentState = SkuState.Listed

            mockMvc.perform(
                post("/admin/dev/sku/$id/list")
                    .header("Authorization", basicHeader("admin", "secret")),
            )
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.skuId").value(id.toString()))
                .andExpect(jsonPath("$.state").value("LISTED"))

            assert(answer.transitionCalls.isEmpty()) {
                "transition must NOT be called when SKU is already Listed; was ${answer.transitionCalls}"
            }
        }

        @Test
        fun `T-40 repeated calls produce consistent 202 responses (idempotent)`() {
            val id = UUID.randomUUID()
            val header = basicHeader("admin", "secret")

            val first = mockMvc.perform(
                post("/admin/dev/sku/$id/list").header("Authorization", header),
            ).andExpect(status().isAccepted).andReturn()

            val second = mockMvc.perform(
                post("/admin/dev/sku/$id/list").header("Authorization", header),
            ).andExpect(status().isAccepted).andReturn()

            val firstBody = first.response.contentAsString
            val secondBody = second.response.contentAsString
            assert(firstBody == secondBody) {
                "Idempotent response bodies must match: '$firstBody' vs '$secondBody'"
            }

            // First hit transition, second short-circuited → exactly one call.
            assert(answer.transitionCalls.size == 1) {
                "Exactly one transition should have occurred, got ${answer.transitionCalls.size}"
            }
            assert(answer.transitionCalls[0].second === SkuState.Listed) {
                "Transition should be to Listed, got ${answer.transitionCalls[0].second}"
            }
        }

        @Test
        fun `T-41 constant-time comparison — regression guard via source inspection`() {
            // Timing attacks aren't reliably testable at unit-test granularity. This
            // is a regression guard: the source must use MessageDigest.isEqual (not
            // String.equals / "==" / contentEquals) for the token comparison.
            val source = java.nio.file.Paths.get(
                System.getProperty("user.dir"),
                "src/main/kotlin/com/autoshipper/catalog/handler/DevAdminController.kt",
            )
            val text = if (java.nio.file.Files.exists(source)) {
                java.nio.file.Files.readString(source)
            } else {
                java.nio.file.Files.readString(
                    java.nio.file.Paths.get(
                        "modules/catalog/src/main/kotlin/com/autoshipper/catalog/handler/DevAdminController.kt",
                    ),
                )
            }
            assert(text.contains("MessageDigest.isEqual")) {
                "DevAdminController must use MessageDigest.isEqual for constant-time token comparison"
            }
            assert(!Regex("""presentedToken\s*==\s*devToken""").containsMatchIn(text)) {
                "DevAdminController must not use == for token comparison (timing-attack vector)"
            }
        }
    }
}
