package org.stellar.anchor.platform.action

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_STATUS_CHANGED
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_38
import org.stellar.anchor.api.rpc.action.NotifyOffchainFundsAvailableRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.util.GsonUtils

class NotifyOffchainFundsAvailableHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val EXTERNAL_TX_ID = "testExternalTxId"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
  }

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter

  private lateinit var handler: NotifyOffchainFundsAvailableHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.handler =
      NotifyOffchainFundsAvailableHandler(
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = NotifyOffchainFundsAvailableRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()
    val spyTxn24 = spyk(txn24)

    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[notify_offchain_funds_available] is not supported. Status[pending_anchor], kind[null], protocol[38], funds received[true]",
      ex.message
    )
  }

  @Test
  fun test_handle_unsupportedStatus() {
    val request = NotifyOffchainFundsAvailableRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_TRUST.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[notify_offchain_funds_available] is not supported. Status[pending_trust], kind[withdrawal], protocol[24], funds received[true]",
      ex.message
    )
  }

  @Test
  fun test_handle_unsupportedKind() {
    val request = NotifyOffchainFundsAvailableRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[notify_offchain_funds_available] is not supported. Status[pending_anchor], kind[deposit], protocol[24], funds received[true]",
      ex.message
    )
  }

  @Test
  fun test_handle_transferNotReceived() {
    val request = NotifyOffchainFundsAvailableRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[notify_offchain_funds_available] is not supported. Status[pending_anchor], kind[withdrawal], protocol[24], funds received[false]",
      ex.message
    )
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = NotifyOffchainFundsAvailableRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { requestValidator.validate(request) } throws
      InvalidParamsException(VALIDATION_ERROR_MESSAGE)

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(VALIDATION_ERROR_MESSAGE, ex.message?.trimIndent())
  }

  @Test
  fun test_handle_ok_deposit_withExternalTxId() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyOffchainFundsAvailableRequest.builder()
        .transactionId(TX_ID)
        .externalTransactionId(EXTERNAL_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = transferReceivedAt
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    mockkStatic(Metrics::class)

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every {
      Metrics.counter("sep24.transaction", "status", "pending_user_transfer_complete")
    } returns sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_COMPLETE.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = transferReceivedAt
    expectedSep24Txn.externalTransactionId = EXTERNAL_TX_ID

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_COMPLETE
    expectedResponse.externalTransactionId = EXTERNAL_TX_ID
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.amountExpected = Amount(null, "")

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_24.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_ok_deposit_withoutExternalTxId() {
    val transferReceivedAt = Instant.now()
    val request = NotifyOffchainFundsAvailableRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = transferReceivedAt
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    mockkStatic(Metrics::class)

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every {
      Metrics.counter("sep24.transaction", "status", "pending_user_transfer_complete")
    } returns sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_COMPLETE.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = transferReceivedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_COMPLETE
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.amountExpected = Amount(null, "")

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_24.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }
}
