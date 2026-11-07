package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MerchantEnrichmentTest {
    @Test fun sameTransactionRepairsSocialOrNavigationCopyWithoutChangingMoneyOrUserEdits() {
        for(copy in listOf("附近门店>","等2000+人喜欢")) {
            val old=(store.ingest(capture(copy),bill(copy)) as IngestResult.Saved).record
            store.saveEdited(old.copy(note="保留备注",bill=old.bill.copy(category="用户分类")))
            store.ingest(capture("合成餐馆",1000),bill("合成餐馆"),merchantIcon=byteArrayOf(9))
            val corrected=store.get(old.id)!!
            assertEquals("合成餐馆",corrected.bill.merchant)
            assertEquals("保留备注",corrected.note);assertEquals("用户分类",corrected.bill.category)
            assertEquals(old.bill.amountCents,corrected.bill.amountCents);assertEquals(old.uid,corrected.uid)
            assertArrayEquals(byteArrayOf(9),store.merchantIcon(old.id))
            store.delete(old.id)
        }
    }

    @Test fun retainedScreenshotCanRepairInterfaceCopyButCannotReplaceUserAuthoredName() {
        val copy="等1万+人喜欢"
        val old=(store.ingest(capture(copy),bill(copy)) as IngestResult.Saved).record
        assertTrue(store.merchantLabelRepairCandidates().any { it.id==old.id })
        assertFalse(store.repairMerchantLabel(old.id,bill("合成餐馆").copy(amountCents=200),null))
        assertTrue(store.repairMerchantLabel(old.id,bill("合成餐馆"),byteArrayOf(9)))
        assertEquals("合成餐馆",store.get(old.id)!!.bill.merchant)
        store.saveEdited(store.get(old.id)!!.copy(bill=bill("我的门店")))
        assertFalse(store.repairMerchantLabel(old.id,bill("合成餐馆"),null))
    }
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="merchant-enrichment-${UUID.randomUUID()}.db"
    private val store=LedgerStore(context,name).prepareSyntheticDefaultWallet()
    private val at=System.currentTimeMillis()
    private fun capture(name:String,delta:Long=0)=Capture(Source.OCR,PaymentApps.WECHAT,
        "$name\n-100.60\n支付成功\n商品\n$name\n商户全称\n合成市${name}有限公司\n交易单号\n4200003144202610117903762623",at+delta)
    private fun bill(name:String)=ParsedBill(10060,Direction.EXPENSE,name,"其他",at,"4200003144202610117903762623",true,"",true)
    @After fun close(){store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name)}

    @Test fun sameTransactionRepairsTruncatedRecognizedNameAndAddsMissingIconWithoutChangingEdits() {
        val old=(store.ingest(capture("合成商"),bill("合成商"),screenshot=byteArrayOf(1)) as IngestResult.Saved).record
        store.saveEdited(old.copy(note="用户备注",bill=old.bill.copy(category="日用购物")))
        val result=store.ingest(capture("合成商贸",1000),bill("合成商贸"),screenshot=byteArrayOf(2),merchantIcon=byteArrayOf(9)) as IngestResult.Saved
        assertEquals(old.id,result.record.id);assertEquals("合成商贸",result.record.bill.merchant)
        assertEquals("合成商贸",store.get(old.id)!!.bill.merchant)
        assertEquals("用户备注",result.record.note);assertEquals("日用购物",result.record.bill.category)
        assertEquals(old.bill.amountCents,result.record.bill.amountCents);assertEquals(old.bill.occurredAt,result.record.bill.occurredAt)
        assertEquals(old.uid,result.record.uid);assertEquals(1,store.all().size)
        assertArrayEquals(byteArrayOf(9),store.merchantIcon(old.id));assertEquals(2,store.captureHistory(old.id).size)
        store.ingest(capture("合成商贸",2000),bill("合成商贸"),screenshot=byteArrayOf(2))
        assertEquals(1,store.all().size);assertArrayEquals(byteArrayOf(9),store.merchantIcon(old.id))
    }

    @Test fun manualShortenedMerchantIsNotReplacedOrGivenAnotherNamesIcon() {
        val old=(store.ingest(capture("合成商贸"),bill("合成商贸")) as IngestResult.Saved).record
        store.saveEdited(old.copy(bill=old.bill.copy(merchant="合成商")))
        store.ingest(capture("合成商贸",1000),bill("合成商贸"),merchantIcon=byteArrayOf(9))
        assertEquals("合成商",store.get(old.id)!!.bill.merchant);assertNull(store.merchantIcon(old.id));assertEquals(1,store.all().size)
    }

    @Test fun oneUncorroboratedPrefixExtensionCannotRenameTheMerchant() {
        val old=(store.ingest(capture("合成商"),bill("合成商")) as IngestResult.Saved).record
        store.ingest(Capture(Source.OCR,PaymentApps.WECHAT,"合成商贸\n-100.60",at+1000),bill("合成商贸"),merchantIcon=byteArrayOf(9))
        assertEquals("合成商",store.get(old.id)!!.bill.merchant);assertNull(store.merchantIcon(old.id))
    }

    @Test fun laterMissingIconAndShorterOrDifferentMerchantCannotDowngradeOriginal() {
        val old=(store.ingest(capture("合成商贸"),bill("合成商贸"),merchantIcon=byteArrayOf(9)) as IngestResult.Saved).record
        store.ingest(capture("合成商",1000),bill("合成商"))
        store.ingest(capture("另一家商户",2000),bill("另一家商户"),merchantIcon=byteArrayOf(8))
        assertEquals("合成商贸",store.get(old.id)!!.bill.merchant);assertArrayEquals(byteArrayOf(9),store.merchantIcon(old.id));assertEquals(1,store.all().size)
    }

    @Test fun sameImageCanGainAnIconOnSecondPassWithoutAddingHistoryOrExpense() {
        val old=(store.ingest(capture("合成商贸"),bill("合成商贸"),screenshot=byteArrayOf(1)) as IngestResult.Saved).record
        store.ingest(capture("合成商贸",1000),bill("合成商贸"),screenshot=byteArrayOf(1),merchantIcon=byteArrayOf(9))
        assertEquals(1,store.all().size);assertEquals(1,store.captureHistory(old.id).size);assertArrayEquals(byteArrayOf(9),store.merchantIcon(old.id))
    }

    @Test fun correctingNameWithoutANewCropKeepsTheExistingIcon() {
        val old=(store.ingest(capture("合成商"),bill("合成商"),merchantIcon=byteArrayOf(9)) as IngestResult.Saved).record
        store.ingest(capture("合成商贸",1000),bill("合成商贸"))
        assertEquals("合成商贸",store.get(old.id)!!.bill.merchant);assertArrayEquals(byteArrayOf(9),store.merchantIcon(old.id))
    }
}
