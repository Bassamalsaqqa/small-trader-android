package com.example.data

import com.example.model.BasketItem
import com.example.model.BluetoothPrinterDevice
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.ExpenseCategory
import com.example.model.ExpenseEntry
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.SaleDraft
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters

object SampleCatalog {

    val defaultBusiness = BusinessInfo()

    val demoStaff = listOf(
        UserSession(
            id = "user_owner",
            name = "أبو كريم (باسم السقا)",
            username = "abu_kareem",
            role = UserRole.OWNER,
            branchNameAr = "الفرع الرئيسي - البلدة القديمة نابلس",
            branchNameEn = "Main Branch - Old City Nablus"
        ),
        UserSession(
            id = "user_manager",
            name = "عمر الصالح",
            username = "omar_s",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع الرئيسي - البلدة القديمة نابلس",
            branchNameEn = "Main Branch - Old City Nablus"
        ),
        UserSession(
            id = "user_cashier",
            name = "طارق الكردي",
            username = "tareq_k",
            role = UserRole.CASHIER,
            branchNameAr = "نقطة بيع الكاشير #01",
            branchNameEn = "POS Station #01"
        )
    )

    val initialProducts: List<Product> = listOf(
        Product(
            id = "p_olive_oil",
            nameAr = "زيت زيتون بلدي بكر ممتاز - معصرة جفنا الأولى",
            nameEn = "Extra Virgin Baladi Olive Oil - Jifna First Press",
            category = ProductCategory.PANTRY,
            barcode = "6251001001014",
            priceIls = 38.00,
            costIls = 29.00,
            unitAr = "زجاجة 1 لتر",
            unitEn = "1L Bottle",
            stockCount = 18,
            minStockThreshold = 8,
            accentHex = 0xFF1D4E89,
            lotBatchCode = "JIFNA-2026-10",
            expiryDate = "2028-10-30"
        ),
        Product(
            id = "p_zaatar_jenin",
            nameAr = "زعتر بلدي جنين بالسماق الجبلي والسمسم المحمص",
            nameEn = "Jenin Baladi Za'atar w/ Mountain Sumac & Sesame",
            category = ProductCategory.ROASTERY,
            barcode = "6251001001021",
            priceIls = 16.50,
            costIls = 11.50,
            unitAr = "كيس 500 غم (وزني)",
            unitEn = "500g Pack (Weighted)",
            stockCount = 24,
            minStockThreshold = 10,
            accentHex = 0xFF285E61,
            warehouseAr = "قسم المحمصة والتعبئة - نابلس",
            warehouseEn = "Roastery & Packing Section - Nablus",
            lotBatchCode = "JENIN-Z26",
            expiryDate = "2027-06-15",
            isWeightedDisplayOnly = true
        ),
        Product(
            id = "p_nabulsi_cheese",
            nameAr = "جبنة نابلسية بلدية مغلية",
            nameEn = "Traditional Nabulsi White Cheese",
            category = ProductCategory.DAIRY,
            barcode = "6251001001038",
            priceIls = 32.00,
            costIls = 24.00,
            unitAr = "عبوة 1 كغم (وزني)",
            unitEn = "1kg Tub (Weighted)",
            stockCount = 9,
            minStockThreshold = 6,
            accentHex = 0xFF3E5A68,
            warehouseAr = "ثلاجة الألبان المركزية #1",
            warehouseEn = "Central Dairy Chiller #1",
            lotBatchCode = "NBL-CH-44",
            expiryDate = "2027-03-01",
            isWeightedDisplayOnly = true
        ),
        Product(
            id = "p_tahini_nablus",
            nameAr = "طحينة نابلسية صافية (السمسم الذهبي)",
            nameEn = "Nablus Pure Sesame Tahini",
            category = ProductCategory.PANTRY,
            barcode = "6251001001045",
            priceIls = 22.00,
            costIls = 16.00,
            unitAr = "مرتبان 800 غم",
            unitEn = "800g Jar",
            stockCount = 15,
            minStockThreshold = 5,
            accentHex = 0xFF334155,
            lotBatchCode = "TAH-2026-09",
            expiryDate = "2027-09-30"
        ),
        Product(
            id = "p_jebrini_milk",
            nameAr = "حليب طازج كامل الدسم (الجبريني)",
            nameEn = "Fresh Full Cream Milk",
            category = ProductCategory.DAIRY,
            barcode = "6251001001052",
            priceIls = 7.50,
            costIls = 5.80,
            unitAr = "عبوة 1 لتر",
            unitEn = "1L Carton",
            stockCount = 5,
            minStockThreshold = 8,
            accentHex = 0xFF2B5876,
            warehouseAr = "ثلاجة العرض الأمامية",
            warehouseEn = "Front Display Chiller",
            lotBatchCode = "JBR-1104",
            expiryDate = "2026-11-14"
        ),
        Product(
            id = "p_labaneh_baladi",
            nameAr = "لبنة بلدية",
            nameEn = "Strained Labaneh",
            category = ProductCategory.DAIRY,
            barcode = "6251001001069",
            priceIls = 11.00,
            costIls = 8.20,
            unitAr = "علبة 500 غم",
            unitEn = "500g Tub",
            stockCount = 7,
            minStockThreshold = 6,
            accentHex = 0xFF457B9D,
            warehouseAr = "ثلاجة العرض الأمامية",
            warehouseEn = "Front Display Chiller",
            lotBatchCode = "LAB-1102",
            expiryDate = "2026-11-25"
        ),
        Product(
            id = "p_arabic_coffee",
            nameAr = "قهوة عربية محمصة مع هيل أخضر",
            nameEn = "Roasted Arabic Coffee w/ Cardamom",
            category = ProductCategory.ROASTERY,
            barcode = "6251001001076",
            priceIls = 18.00,
            costIls = 13.00,
            unitAr = "كيس 250 غم",
            unitEn = "250g Bag",
            stockCount = 30,
            minStockThreshold = 10,
            accentHex = 0xFF334155
        ),
        Product(
            id = "p_freekeh_jenin",
            nameAr = "فريكة جنين خضراء محمصة نخب أول",
            nameEn = "Roasted Green Freekeh Grade A",
            category = ProductCategory.PANTRY,
            barcode = "6251001001083",
            priceIls = 19.00,
            costIls = 14.50,
            unitAr = "كيس 1 كغم",
            unitEn = "1kg Bag",
            stockCount = 14,
            minStockThreshold = 6,
            accentHex = 0xFF285E61
        ),
        Product(
            id = "p_maftoul_baladi",
            nameAr = "مفتول بلدي مفتول يدوياً",
            nameEn = "Hand-Rolled Palestinian Maftoul",
            category = ProductCategory.PANTRY,
            barcode = "6251001001090",
            priceIls = 14.00,
            costIls = 10.00,
            unitAr = "كيس 1 كغم",
            unitEn = "1kg Pack",
            stockCount = 12,
            minStockThreshold = 5,
            accentHex = 0xFF1D4E89
        ),
        Product(
            id = "p_halva_pistachio",
            nameAr = "حلاوة طحينية نابلسية بالفستق",
            nameEn = "Pistachio Halva Confection",
            category = ProductCategory.PANTRY,
            barcode = "6251001001106",
            priceIls = 17.50,
            costIls = 12.80,
            unitAr = "علبة 500 غم",
            unitEn = "500g Box",
            stockCount = 11,
            minStockThreshold = 5,
            accentHex = 0xFF334155
        ),
        Product(
            id = "p_medjool_dates",
            nameAr = "تمر مجهول أريحا جامبو فاخر",
            nameEn = "Jericho Jumbo Medjool Dates",
            category = ProductCategory.ROASTERY,
            barcode = "6251001001113",
            priceIls = 28.00,
            costIls = 21.00,
            unitAr = "عبوة 1 كغم",
            unitEn = "1kg Box",
            stockCount = 4,
            minStockThreshold = 6,
            accentHex = 0xFF475569
        ),
        Product(
            id = "p_ghazaleen_tea",
            nameAr = "شاي سيلاني فاخر",
            nameEn = "Premium Ceylon Black Tea",
            category = ProductCategory.BEVERAGES,
            barcode = "6251001001120",
            priceIls = 15.00,
            costIls = 11.00,
            unitAr = "علبة 400 غم",
            unitEn = "400g Pack",
            stockCount = 20,
            minStockThreshold = 8,
            accentHex = 0xFF1D4E89
        ),
        Product(
            id = "p_rice_medium",
            nameAr = "أرز حبة متوسطة فاخر",
            nameEn = "Premium Medium Grain Rice",
            category = ProductCategory.PANTRY,
            barcode = "6251001001137",
            priceIls = 34.00,
            costIls = 26.50,
            unitAr = "كيس 5 كغم",
            unitEn = "5kg Sack",
            stockCount = 3,
            minStockThreshold = 5,
            accentHex = 0xFF285E61
        ),
        Product(
            id = "p_nabulsi_soap",
            nameAr = "صابون نابلسي أصلي بزيت الزيتون",
            nameEn = "Pure Olive Oil Nabulsi Soap",
            category = ProductCategory.HOUSEHOLD,
            barcode = "6251001001144",
            priceIls = 6.00,
            costIls = 3.90,
            unitAr = "قطعة 140 غم",
            unitEn = "140g Bar",
            stockCount = 40,
            minStockThreshold = 12,
            accentHex = 0xFF475569
        ),
        Product(
            id = "p_jericho_water",
            nameAr = "مياه معدنية طبيعية (6 عبوات)",
            nameEn = "Natural Spring Water (6-Pack)",
            category = ProductCategory.BEVERAGES,
            barcode = "6251001001151",
            priceIls = 10.00,
            costIls = 7.00,
            unitAr = "شدة 6 × 1.5 لتر",
            unitEn = "6 x 1.5L Pack",
            stockCount = 16,
            minStockThreshold = 6,
            accentHex = 0xFF0077B6
        ),
        Product(
            id = "p_sugar_white",
            nameAr = "سكر ناعم",
            nameEn = "Fine Sugar",
            category = ProductCategory.PANTRY,
            barcode = "6251001001168",
            priceIls = 5.50,
            costIls = 4.10,
            unitAr = "كيس 1 كغم",
            unitEn = "1kg Bag",
            stockCount = 25,
            minStockThreshold = 10,
            accentHex = 0xFF4A5759
        ),
        Product(
            id = "p_baladi_ghee",
            nameAr = "سمن بلدي غنم صافي - دير غسانة",
            nameEn = "Pure Baladi Sheep Ghee - Deir Ghassaneh",
            category = ProductCategory.DAIRY,
            barcode = "6251001001175",
            priceIls = 65.00,
            costIls = 52.00,
            unitAr = "مرتبان 600 غم",
            unitEn = "600g Jar",
            stockCount = 0,
            minStockThreshold = 4,
            accentHex = 0xFF334155
        ),
        Product(
            id = "p_roasting_service",
            nameAr = "خدمة طحن وتحميص قهوة وبهارات (حسب الطلب)",
            nameEn = "Custom Coffee & Spice Grinding Service",
            category = ProductCategory.ROASTERY,
            barcode = "6251001001182",
            priceIls = 5.00,
            costIls = 0.00,
            unitAr = "خدمة فورية",
            unitEn = "Service Line",
            stockCount = 0,
            minStockThreshold = 0,
            accentHex = 0xFF1D4E89,
            isStockTracked = false
        )
    )

    val initialCustomers = listOf(
        Customer(
            id = "cust_01",
            nameAr = "أبو طارق (محمود عبد الرحمن دويكات وأولاده)",
            nameEn = "Abu Tareq (Mahmoud Dweikat & Sons)",
            phone = "0599-123456",
            addressAr = "الحارة الشرقية، نابلس",
            addressEn = "East Quarter, Nablus",
            balanceIls = 185.00,
            creditLimitIls = 1500.0,
            totalPurchasesIls = 3450.00
        ),
        Customer(
            id = "cust_02",
            nameAr = "أم سامر المصري",
            nameEn = "Um Samer Al-Masri",
            phone = "0598-654321",
            addressAr = "رفيديا، نابلس",
            addressEn = "Rafidia, Nablus",
            balanceIls = 90.00,
            creditLimitIls = 800.0,
            totalPurchasesIls = 1920.00
        ),
        Customer(
            id = "cust_03",
            nameAr = "أحمد القواسمي",
            nameEn = "Ahmad Al-Qawasmi",
            phone = "0569-789012",
            addressAr = "الدوار، نابلس",
            addressEn = "Downtown Clock Tower, Nablus",
            balanceIls = 0.00,
            creditLimitIls = 1000.0,
            totalPurchasesIls = 1140.00
        ),
        Customer(
            id = "cust_04",
            nameAr = "شركة مطعم ومقهى الياسمين التراثي - خان التجار",
            nameEn = "Al-Yasmeen Heritage Restaurant & Cafe - Khan Al-Tujjar",
            phone = "0597-334455",
            addressAr = "خان التجار، البلدة القديمة، نابلس",
            addressEn = "Khan Al-Tujjar, Old City, Nablus",
            balanceIls = 460.00,
            creditLimitIls = 3000.0,
            totalPurchasesIls = 8720.00
        ),
        Customer(
            id = "cust_05",
            nameAr = "الحاج نبيل الشنار (مخبز وحلويات الشنار)",
            nameEn = "Hajj Nabil Al-Shannar (Al-Shannar Bakery)",
            phone = "0595-210987",
            addressAr = "شارع فيصل، نابلس",
            addressEn = "Faisal Street, Nablus",
            balanceIls = -50.00,
            creditLimitIls = 1200.0,
            totalPurchasesIls = 2150.00
        )
    )

    val initialCustomerTransactions = listOf(
        CustomerTransaction(
            id = "ctx_101",
            customerId = "cust_01",
            date = "${WesternFormatters.currentDate()} 11:45",
            typeAr = "فاتورة مبيعات #INV-1042",
            typeEn = "Sales Invoice #INV-1042",
            invoiceNumber = 1042,
            debitIls = 85.00,
            creditIls = 85.00,
            runningBalanceIls = 185.00,
            note = "دفعة نقدية فورية كاملة"
        ),
        CustomerTransaction(
            id = "ctx_102",
            customerId = "cust_01",
            date = "2026-11-02 16:30",
            typeAr = "مشتريات على الحساب (آجل)",
            typeEn = "Credit Purchase",
            invoiceNumber = 1034,
            debitIls = 185.00,
            creditIls = 0.00,
            runningBalanceIls = 185.00,
            note = "زيت وسكر وتموين"
        ),
        CustomerTransaction(
            id = "ctx_103_paid",
            customerId = "cust_02",
            date = "${WesternFormatters.currentDate()} 10:20",
            typeAr = "فاتورة مبيعات #INV-1041",
            typeEn = "Sales Invoice #INV-1041",
            invoiceNumber = 1041,
            debitIls = 61.00,
            creditIls = 61.00,
            runningBalanceIls = 90.00,
            note = "دفعة نقدية فورية كاملة"
        ),
        CustomerTransaction(
            id = "ctx_103",
            customerId = "cust_02",
            date = "2026-11-03 14:10",
            typeAr = "مشتريات آجل #INV-1038",
            typeEn = "Credit Purchase #INV-1038",
            invoiceNumber = 1038,
            debitIls = 90.00,
            creditIls = 0.00,
            runningBalanceIls = 90.00,
            note = "ألبان وأجبان"
        ),
        CustomerTransaction(
            id = "ctx_106",
            customerId = "cust_04",
            date = "2026-11-02 18:20",
            typeAr = "فاتورة مبيعات مدفوعة جزئياً #INV-1036",
            typeEn = "Partially Paid Invoice #INV-1036",
            invoiceNumber = 1036,
            debitIls = 158.00,
            creditIls = 58.00,
            runningBalanceIls = 460.00,
            note = "إجمالي الفاتورة 158.00 ₪ • مدفوع نقداً 58.00 ₪ • متبقي بالذمة 100.00 ₪"
        ),
        CustomerTransaction(
            id = "ctx_104",
            customerId = "cust_04",
            date = "2026-11-01 10:00",
            typeAr = "دفعة نقدية من الحساب",
            typeEn = "Payment on Account",
            invoiceNumber = null,
            debitIls = 0.00,
            creditIls = 500.00,
            runningBalanceIls = 360.00,
            note = "سند قبض نقدي من رصيد سابق (860.00 ₪)"
        ),
        CustomerTransaction(
            id = "ctx_105",
            customerId = "cust_05",
            date = "2026-11-02 09:30",
            typeAr = "دفعة مقدمة على الحساب (رصيد للزبون)",
            typeEn = "Advance Deposit on Account",
            invoiceNumber = null,
            debitIls = 0.00,
            creditIls = 50.00,
            runningBalanceIls = -50.00,
            note = "دفعة مقدمة لطلبيات الأسبوع"
        )
    )

    val initialSuppliers = listOf(
        Supplier(
            id = "sup_01",
            nameAr = "معصرة الزيتون البلدية (جنين)",
            nameEn = "Baladi Olive Press (Jenin)",
            companyAr = "معاصر جنين ومحيطها",
            companyEn = "Jenin Olive Mills Co.",
            phone = "0599-445566",
            balanceOwedIls = 1200.00
        ),
        Supplier(
            id = "sup_02",
            nameAr = "شركة الجبريني لمنتجات الألبان",
            nameEn = "Al-Jebrini Dairy Industry",
            companyAr = "ألبان الجبريني - الخليل",
            companyEn = "Al-Jebrini Dairy - Hebron",
            phone = "02-2228899",
            balanceOwedIls = 450.00
        ),
        Supplier(
            id = "sup_03",
            nameAr = "مطاحن ومحامص نابلس المركزية",
            nameEn = "Nablus Central Mills & Roasters",
            companyAr = "شركة مطاحن نابلس الكبرى",
            companyEn = "Nablus Grand Mills Co.",
            phone = "09-2345678",
            balanceOwedIls = 850.00
        )
    )

    val initialPurchaseBills = listOf(
        PurchaseBill(
            id = "pb_01",
            billNumber = 301,
            supplierId = "sup_01",
            date = "2026-11-01",
            totalIls = 1750.00,
            paidIls = 550.00,
            itemsSummary = "20 زجاجة زيت زيتون بلدي بكر ممتاز (1 لتر) — معصرة جنين",
            isReceived = true,
            expectedProductId = "p_olive_oil",
            expectedQuantity = 20
        ),
        PurchaseBill(
            id = "pb_02",
            billNumber = 302,
            supplierId = "sup_02",
            date = "2026-11-03",
            totalIls = 650.00,
            paidIls = 200.00,
            itemsSummary = "25 عبوة حليب طازج كامل الدسم (الجبريني) — توريد مبرد",
            isReceived = true,
            expectedProductId = "p_jebrini_milk",
            expectedQuantity = 25
        ),
        PurchaseBill(
            id = "pb_03",
            billNumber = 303,
            supplierId = "sup_03",
            date = "2026-11-04",
            totalIls = 850.00,
            paidIls = 0.00,
            itemsSummary = "12 كيس أرز حبة متوسطة فاخر (5 كغم) — توريد مطاحن نابلس",
            isReceived = false,
            expectedProductId = "p_rice_medium",
            expectedQuantity = 12
        )
    )

    val initialExpenses = listOf(
        ExpenseEntry(
            id = "exp_01",
            date = WesternFormatters.currentDate(),
            titleAr = "شحن ونقل طلبيات من معصرة جنين",
            titleEn = "Freight from Jenin Olive Press",
            category = ExpenseCategory.TRANSPORT,
            amountIls = 60.00
        ),
        ExpenseEntry(
            id = "exp_02",
            date = "2026-11-03",
            titleAr = "فاتورة كهرباء المحل والمبردات",
            titleEn = "Store Electricity & Chillers",
            category = ExpenseCategory.ELECTRICITY_WATER,
            amountIls = 180.00
        ),
        ExpenseEntry(
            id = "exp_03",
            date = "2026-11-02",
            titleAr = "رول ورق طابعات حرارية 58mm و80mm",
            titleEn = "Thermal Paper Rolls 58mm/80mm",
            category = ExpenseCategory.MAINTENANCE,
            amountIls = 45.00
        )
    )

    val initialStockMovements = listOf(
        StockMovement(
            id = "sm_01",
            productId = "p_olive_oil",
            date = "${WesternFormatters.currentDate()} 11:45",
            delta = -1,
            reasonAr = "مبيعات نقدية فاتورة #INV-1042",
            reasonEn = "Sales Invoice #INV-1042",
            resultingStock = 18
        ),
        StockMovement(
            id = "sm_02",
            productId = "p_zaatar_jenin",
            date = "${WesternFormatters.currentDate()} 11:45",
            delta = -2,
            reasonAr = "مبيعات نقدية فاتورة #INV-1042",
            reasonEn = "Sales Invoice #INV-1042",
            resultingStock = 24
        ),
        StockMovement(
            id = "sm_03",
            productId = "p_olive_oil",
            date = "2026-11-01 10:00",
            delta = +20,
            reasonAr = "استلام بضاعة فاتورة شراء #BILL-0301",
            reasonEn = "Purchase Receiving #BILL-0301",
            resultingStock = 19
        )
    )

    val simulatedPrinters: List<BluetoothPrinterDevice> = listOf(
        BluetoothPrinterDevice(
            id = "bt_alquds_58",
            name = "Al-Quds Thermal POS-58",
            address = "DC:0D:30:7A:41:19",
            paperWidthMm = 58,
            batteryPercent = 92,
            isPaired = true
        ),
        BluetoothPrinterDevice(
            id = "bt_xprinter_80",
            name = "Xprinter XP-P300 (80mm)",
            address = "88:C6:26:B1:09:4E",
            paperWidthMm = 80,
            batteryPercent = 78,
            isPaired = true
        ),
        BluetoothPrinterDevice(
            id = "bt_sunmi_v2",
            name = "Sunmi V2 Built-In Printer",
            address = "00:11:22:AA:BB:CC",
            paperWidthMm = 58,
            batteryPercent = 100,
            isPaired = true
        ),
        BluetoothPrinterDevice(
            id = "bt_star_l200",
            name = "Star Micronics SM-L200",
            address = "54:6C:0E:99:12:F3",
            paperWidthMm = 58,
            batteryPercent = 64,
            isPaired = false
        )
    )

    fun initialSaleDrafts(products: List<Product>): List<SaleDraft> {
        val byId = products.associateBy { it.id }
        val draftItems = listOfNotNull(
            byId["p_olive_oil"]?.let { BasketItem(it, 2) },
            byId["p_tahini_nablus"]?.let { BasketItem(it, 1) }
        )
        return listOf(
            SaleDraft(
                id = "draft_101",
                titleAr = "مسودة طلبية مطعم الياسمين التراثي",
                titleEn = "Al-Yasmeen Restaurant Order Draft",
                timestamp = "${WesternFormatters.currentDate()} 08:40",
                items = draftItems,
                customerId = "cust_04",
                customerNote = "تجهيز للاستلام ظهراً",
                subtotalIls = 98.00,
                netTotalIls = 98.00
            )
        )
    }

    fun initialInvoices(products: List<Product>): List<InvoiceRecord> {
        val byId = products.associateBy { it.id }
        val inv1Items = listOfNotNull(
            byId["p_olive_oil"]?.let { BasketItem(it, 1) },
            byId["p_zaatar_jenin"]?.let { BasketItem(it, 2) },
            byId["p_jebrini_milk"]?.let { BasketItem(it, 2) }
        )
        val inv2Items = listOfNotNull(
            byId["p_nabulsi_cheese"]?.let { BasketItem(it, 1) },
            byId["p_arabic_coffee"]?.let { BasketItem(it, 1) },
            byId["p_labaneh_baladi"]?.let { BasketItem(it, 1) }
        )
        val inv3Items = listOfNotNull(
            byId["p_tahini_nablus"]?.let { BasketItem(it, 1) },
            byId["p_halva_pistachio"]?.let { BasketItem(it, 1) },
            byId["p_sugar_white"]?.let { BasketItem(it, 2) }
        )

        val inv4CreditItems = listOfNotNull(
            byId["p_nabulsi_cheese"]?.let { BasketItem(it, 2) }, // 2 * 32.00 = 64.00
            byId["p_labaneh_baladi"]?.let { BasketItem(it, 1) }, // 1 * 11.00 = 11.00
            byId["p_jebrini_milk"]?.let { BasketItem(it, 2) }    // 2 * 7.50  = 15.00 -> Total 90.00
        )
        val invPartialItems = listOfNotNull(
            byId["p_olive_oil"]?.let { BasketItem(it, 3) },      // 3 * 38.00 = 114.00
            byId["p_tahini_nablus"]?.let { BasketItem(it, 2) }   // 2 * 22.00 = 44.00 -> Total 158.00
        )
        val inv5LongCreditItems = listOfNotNull(
            byId["p_olive_oil"]?.let { BasketItem(it, 2) },      // 2 * 38.00 = 76.00
            byId["p_rice_medium"]?.let { BasketItem(it, 1) },    // 1 * 34.00 = 34.00
            byId["p_zaatar_jenin"]?.let { BasketItem(it, 1) },   // 1 * 16.50 = 16.50
            byId["p_tahini_nablus"]?.let { BasketItem(it, 1) },  // 1 * 22.00 = 22.00
            byId["p_ghazaleen_tea"]?.let { BasketItem(it, 1) },  // 1 * 15.00 = 15.00
            byId["p_sugar_white"]?.let { BasketItem(it, 2) },    // 2 * 5.50  = 11.00
            byId["p_nabulsi_soap"]?.let { BasketItem(it, 2) }    // 2 * 6.00  = 12.00 -> Subtotal 186.50, Discount 1.50, Net 185.00
        )

        return listOf(
            InvoiceRecord(
                id = "inv_1042",
                invoiceNumber = 1042,
                timestamp = "${WesternFormatters.currentDate()} 11:45",
                items = inv1Items,
                subtotalIls = 86.00,
                discountIls = 1.00,
                netTotalIls = 85.00,
                cashReceivedIls = 100.00,
                changeReturnedIls = 15.00,
                customerId = "cust_01",
                customerNote = "أبو طارق (محمود دويكات)",
                cashierNameAr = "أبو كريم",
                cashierNameEn = "Abu Kareem",
                isPaidInFull = true,
                remainingDebitIls = 0.0,
                printCount = 1
            ),
            InvoiceRecord(
                id = "inv_1041",
                invoiceNumber = 1041,
                timestamp = "${WesternFormatters.currentDate()} 10:20",
                items = inv2Items,
                subtotalIls = 61.00,
                discountIls = 0.00,
                netTotalIls = 61.00,
                cashReceivedIls = 70.00,
                changeReturnedIls = 9.00,
                customerId = "cust_02",
                customerNote = "أم سامر المصري",
                cashierNameAr = "أبو كريم",
                cashierNameEn = "Abu Kareem",
                isPaidInFull = true,
                remainingDebitIls = 0.0,
                printCount = 1
            ),
            InvoiceRecord(
                id = "inv_1040",
                invoiceNumber = 1040,
                timestamp = "${WesternFormatters.currentDate()} 09:05",
                items = inv3Items,
                subtotalIls = 50.50,
                discountIls = 0.50,
                netTotalIls = 50.00,
                cashReceivedIls = 50.00,
                changeReturnedIls = 0.00,
                customerId = null,
                customerNote = "زبون نقدي",
                cashierNameAr = "أبو كريم",
                cashierNameEn = "Abu Kareem",
                isPaidInFull = true,
                remainingDebitIls = 0.0,
                printCount = 2
            ),
            InvoiceRecord(
                id = "inv_1038",
                invoiceNumber = 1038,
                timestamp = "2026-11-03 14:10",
                items = inv4CreditItems,
                subtotalIls = 90.00,
                discountIls = 0.00,
                netTotalIls = 90.00,
                cashReceivedIls = 0.00,
                changeReturnedIls = 0.00,
                customerId = "cust_02",
                customerNote = "أم سامر المصري • ألبان وأجبان",
                cashierNameAr = "أبو كريم",
                cashierNameEn = "Abu Kareem",
                isPaidInFull = false,
                remainingDebitIls = 90.00,
                printCount = 1
            ),
            InvoiceRecord(
                id = "inv_1036",
                invoiceNumber = 1036,
                timestamp = "2026-11-02 18:20",
                items = invPartialItems,
                subtotalIls = 158.00,
                discountIls = 0.00,
                netTotalIls = 158.00,
                cashReceivedIls = 58.00,
                changeReturnedIls = 0.00,
                customerId = "cust_04",
                customerNote = "شركة مطعم ومقهى الياسمين التراثي • دفعة أولى 58 ₪ والباقي بالذمة",
                cashierNameAr = "أبو كريم",
                cashierNameEn = "Abu Kareem",
                isPaidInFull = false,
                remainingDebitIls = 100.00,
                printCount = 1
            ),
            InvoiceRecord(
                id = "inv_1034",
                invoiceNumber = 1034,
                timestamp = "2026-11-02 16:30",
                items = inv5LongCreditItems,
                subtotalIls = 186.50,
                discountIls = 1.50,
                netTotalIls = 185.00,
                cashReceivedIls = 0.00,
                changeReturnedIls = 0.00,
                customerId = "cust_01",
                customerNote = "أبو طارق (محمود عبد الرحمن دويكات وأولاده) • زيت وسكر وتموين",
                cashierNameAr = "أبو كريم",
                cashierNameEn = "Abu Kareem",
                isPaidInFull = false,
                remainingDebitIls = 185.00,
                printCount = 0
            )
        )
    }
}
