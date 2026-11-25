package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.mobile.offline.RestrictedOfflineWorkspace
import com.example.model.BasketItem
import com.example.model.InvoiceRecord
import com.example.model.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

@Composable
internal fun RestrictedOfflineScreen(baseOrigin: String, onRetry: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val workspace by produceState<RestrictedOfflineWorkspace?>(null, context) {
        val loaded = RestrictedOfflineWorkspace.open(context, baseOrigin)
        value = loaded
        awaitDispose { loaded?.close() }
    }
    val active = workspace
    var valid by remember(active) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var basket by remember(active) { mutableStateOf<List<BasketItem>>(emptyList()) }
    var tender by remember { mutableStateOf("") }
    var eventId by remember(active) { mutableStateOf(com.example.data.mobile.contract.ClientUuid.random().value) }
    val quantities = remember(active) { mutableStateMapOf<String, String>() }
    val prices = remember(active) { mutableStateMapOf<String, String>() }
    var pending by remember { mutableStateOf(0) }
    var rejected by remember { mutableStateOf<List<InvoiceRecord>>(emptyList()) }
    LaunchedEffect(active) {
        if (active != null) while (true) {
            valid = withContext(Dispatchers.IO) { runCatching { active.checkValid() }.isSuccess }
            if (!valid) { basket = emptyList(); pending = 0; rejected = emptyList(); message = ""; tender = ""; break }
            runCatching { active.pending() }.onSuccess { summary -> pending = summary.count; rejected = summary.rejected }
            kotlinx.coroutines.delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Restricted offline sale capture / بيع دون اتصال بصلاحية محدودة", style = MaterialTheme.typography.titleMedium)
        Text("No online authentication or current permissions verified / لم يتم التحقق من الاتصال أو الصلاحيات الحالية")
        if (valid) Text("Pending synchronization / غير مرحّلة بعد: $pending")
        if (!valid) Text("Offline grant unavailable or expired. Queued cash is preserved. / الإذن غير متاح أو منتهي. المبيعات المحفوظة لم تُحذف.")
        Button(onClick = onRetry, enabled = !busy) { Text("Reconnect and verify / الاتصال والتحقق") }
        TextButton(onClick = onLogout, enabled = !busy) { Text("Sign out / تسجيل الخروج") }
        if (active != null && valid) {
            val products by active.products.collectAsState()
            val lease = active.lease
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rejected, key = { "held:${it.id}" }) { receipt ->
                    Text("Cash held for online review / نقد معلّق للمراجعة عبر الاتصال: ${receipt.localReceiptNumber.orEmpty()}")
                }
                items(products, key = { it.id }) { product ->
                    Button(enabled = !busy && product.priceExact != null, onClick = {
                        val existing = basket.find { it.product.id == product.id }
                        basket = if (existing == null) basket + BasketItem(product = product, quantity = BigDecimal.ONE,
                            unitPriceExact = requireNotNull(product.priceExact), taxRateId = lease.taxRateId?.value,
                            taxRateExact = lease.taxRate?.value ?: BigDecimal.ZERO, isTaxInclusive = lease.taxCalculation == "inclusive")
                        else basket.map { if (it === existing) it.copy(quantity = it.quantity.add(BigDecimal.ONE)) else it }
                        val added = basket.first { it.product.id == product.id }
                        quantities[product.id] = added.quantity.toPlainString()
                        prices[product.id] = added.unitPriceExact.toPlainString()
                    }) { Text(product.nameAr + " / " + product.nameEn) }
                }
                items(basket, key = { "basket:${it.product.id}" }) { line ->
                    Text(line.product.nameAr + " / " + line.product.nameEn)
                    OutlinedTextField(value = quantities[line.product.id].orEmpty(), enabled = !busy, onValueChange = { text ->
                        if (text.length <= 32) { quantities[line.product.id] = text; text.takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+)?")) }?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }?.let { amount ->
                            basket = basket.map { if (it.product.id == line.product.id) it.copy(quantity = amount) else it }
                        } }
                    }, label = { Text("Quantity / الكمية") })
                    OutlinedTextField(value = prices[line.product.id].orEmpty(), enabled = !busy, onValueChange = { text ->
                        if (text.length <= 32) { prices[line.product.id] = text; text.takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+)?")) }?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO }?.let { amount ->
                            basket = basket.map { if (it.product.id == line.product.id) it.copy(unitPriceExact = amount) else it }
                        } }
                    }, label = { Text("Unit price / سعر الوحدة") })
                    TextButton(enabled = !busy, onClick = { basket = basket.filter { it.product.id != line.product.id } }) { Text("Remove / حذف") }
                }
            }
            val total = runCatching { basket.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.calculateLine(lease.currencyMinorUnits).lineTotal.value) } }.getOrNull()
            Text("\u2066${total?.toPlainString().orEmpty()} ${lease.currency}\u2069", style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
            OutlinedTextField(value = tender, enabled = !busy, onValueChange = { if (it.length <= 32) tender = it }, label = { Text("Cash tendered / النقد المستلم") })
            val cash = tender.takeIf { it.matches(Regex("[0-9]{1,14}(\\.[0-9]{1,2})?")) }?.toBigDecimalOrNull()
            val entriesValid = basket.all { line ->
                quantities[line.product.id]?.matches(Regex("[0-9]+(\\.[0-9]+)?")) == true &&
                    prices[line.product.id]?.matches(Regex("[0-9]+(\\.[0-9]+)?")) == true &&
                    quantities[line.product.id]?.toBigDecimalOrNull()?.compareTo(line.quantity) == 0 &&
                    prices[line.product.id]?.toBigDecimalOrNull()?.compareTo(line.unitPriceExact) == 0
            }
            Button(enabled = !busy && entriesValid && basket.isNotEmpty() && total != null && cash != null && cash >= total, onClick = {
                if (busy) return@Button
                val settled = requireNotNull(total)
                val received = requireNotNull(cash)
                val invoice = InvoiceRecord(id = eventId, clientEventId = eventId, invoiceNumber = 0, timestamp = lease.issuedAt, items = basket,
                    subtotalExact = settled, discountExact = BigDecimal.ZERO, netTotalExact = settled,
                    cashReceivedExact = received, changeReturnedExact = received.subtract(settled),
                    customerNote = "", cashierNameAr = "", cashierNameEn = "")
                busy = true
                scope.launch {
                    try {
                        val result = active.capture(invoice)
                        message = if (result.isSuccess) "Pending synchronization / غير مرحّلة بعد: ${result.getOrThrow().localReceiptNumber}"
                            else "Capture failed / تعذر الحفظ: ${result.exceptionOrNull()?.message.orEmpty()}"
                        if (result.isSuccess) { eventId = com.example.data.mobile.contract.ClientUuid.random().value; basket = emptyList(); tender = ""; pending = active.pending().count }
                    } finally { busy = false }
                }
            }) { Text("Capture provisional cash sale / حفظ بيع نقدي مؤقت") }
        }
        if (message.isNotEmpty()) Text(message)
    }
}
