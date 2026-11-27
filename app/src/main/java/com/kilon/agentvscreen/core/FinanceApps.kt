package com.kilon.agentvscreen.core

/**
 * Built-in list of mainstream finance, banking, brokerage, and dedicated payment wallet packages
 * that default to `VScreenStore.Rule.ASK` (confirm every time) unless the user explicitly overrides
 * the rule (relaxing to ALLOW requires biometric strong confirmation in Settings).
 *
 * General-purpose chat / super-apps (e.g., WeChat, QQ, Telegram) are intentionally excluded so
 * daily messaging is unaffected; their payment buttons and checkout screens remain protected by
 * `VScreenGame.consentKind` and payment-context `Consent.MONEY` confirmation.
 *
 * Pure Kotlin helper with zero Android dependencies.
 */
object FinanceApps {
    val PACKAGES: Set<String> = setOf(
        // CN dedicated payment & digital currency wallets
        "com.eg.android.AlipayGphone",       // Alipay
        "com.unionpay",                      // UnionPay (Cloud QuickPass)
        "com.unionpay.tsmservice",           // UnionPay TSM
        "cn.gov.pbc.dcep",                   // e-CNY
        "com.jd.jrapp",                      // JD Finance
        // CN major banks
        "com.icbc",                          // ICBC
        "com.android.bankabc",               // ABC
        "com.chinamworld.main",              // CCB
        "com.chinamworld.bocmbci",           // BOC
        "com.bankcomm.Bankcomm",             // BoCom
        "cmb.pb",                            // CMB
        "com.cmbchina.ccd.pluto.cmbActivity",// CMB Palm Life
        "com.yitong.mbank.psbc",             // PSBC
        "com.pingan.paces.ccms",             // Ping An Pocket Bank
        "com.pingan.pocketbank",             // Ping An Pocket Bank (alt)
        "com.ecitic.bank.mobile",            // CITIC Bank
        "com.cebbank.mobile.cemb",           // CEB Bank
        "cn.com.spdb.mobilebank.per",        // SPDB
        "cn.com.cmbc.newmbank",              // CMBC
        "com.cib.cibmb",                     // CIB
        "com.cgbchina.xpt",                  // CGB
        "com.hxb.mobile.client",             // HXB
        // CN securities & wealth
        "com.eastmoney.android.berlin",      // Eastmoney
        "com.hexin.plat.android",            // 10jqka
        "com.lphtsccft",                     // Huatai
        "com.citics.zxzq",                   // CITIC Securities
        "com.futunn.android",                // Futu
        "com.tigerbrokers.stock",            // Tiger Brokers
        // Global wallets, banks & exchanges
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.paypal.android.p2pmobile",      // PayPal
        "com.squareup.cash",                 // Cash App
        "com.venmo",                         // Venmo
        "com.revolut.revolut",               // Revolut
        "com.transferwise.android",          // Wise
        "com.chase.sig.android",             // Chase
        "com.infonow.bofa",                  // Bank of America
        "com.wf.wellsfargomobile",           // Wells Fargo
        "com.citi.citimobile",               // Citi Mobile
        "com.coinbase.android",              // Coinbase
        "com.binance.dev",                   // Binance
        "com.okinc.okex.gp",                 // OKX
    )

    fun isFinancial(pkg: String?): Boolean = !pkg.isNullOrBlank() && pkg in PACKAGES
}
