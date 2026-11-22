package net.ankio.ledger.capture

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankio.ledger.R
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.data.AccountType
import net.ankio.ledger.ledger
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

/** Edits the saved record in an accessibility window over the current app. */
internal class RecognitionBillEditor(private val service: ScreenCaptureService) {
    @Volatile private var dialog: AlertDialog? = null
    val showing get() = dialog != null

    fun close() { dialog?.dismiss() }

    fun show(recordId: Long) {
        val app = service.ledger
        val store = app.store
        var record = store.get(recordId)
        if (record == null || record.state != RecordState.POSTED || record.bill.direction !in directions) {
            Toast.makeText(service, "账单已变化，请在账本中查看", Toast.LENGTH_SHORT).show()
            return
        }
        close()
        val initial = record
        val context = ContextThemeWrapper(service, R.style.AppTheme)
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            isFocusableInTouchMode = true
        }
        val error = TextView(context).apply {
            visibility = View.GONE
            isFocusableInTouchMode = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        form.addView(error)
        fun showError(message: String) {
            error.text = message
            error.visibility = View.VISIBLE
            error.requestFocus()
        }

        val category = Button(context)
        val amount = editText(context, Money.format(initial.bill.amountCents ?: 0L), InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL, 32)
        val wallet = Button(context)
        val note = editText(context, initial.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 500)
        val more = Button(context).apply { text = "更多账单信息"; minHeight = dp(48) }
        val advanced = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val merchant = editText(context, initial.bill.merchant, InputType.TYPE_CLASS_TEXT, 1024)
        val direction = Button(context)
        val zone = ZoneId.systemDefault()
        val originalTime = timeFormat.format(Instant.ofEpochMilli(initial.bill.occurredAt).atZone(zone))
        val time = editText(context, originalTime, InputType.TYPE_CLASS_DATETIME, 16)
        time.hint = "年-月-日 时:分"

        addField(form, "分类", category)
        addField(form, "金额（元）", amount)
        addField(form, "钱包", wallet)
        addField(form, "备注", note)
        form.addView(more)
        form.addView(advanced)
        addField(advanced, "商家", merchant)
        addField(advanced, "时间", time)
        addField(advanced, "收支方向", direction)
        more.setOnClickListener {
            val expand = advanced.visibility != View.VISIBLE
            advanced.visibility = if (expand) View.VISIBLE else View.GONE
            more.text = if (expand) "收起更多信息" else "更多账单信息"
        }

        val categoryByDirection = store.categoryDirectory().groupBy { it.direction }
        val originalDirection = requireNotNull(initial.bill.direction)
        val originalCategory = store.categoryFor(initial)?.id
        var selectedDirection = originalDirection
        var categoryIndex = 0
        var categories = categoryByDirection[selectedDirection].orEmpty()
        fun showCategories(target: Direction) {
            selectedDirection = target
            categories = categoryByDirection[target].orEmpty()
            val preferred = if (target == originalDirection) originalCategory else categories.firstOrNull { it.isFallback }?.id
            categoryIndex = categories.indexOfFirst { it.id == preferred }.coerceAtLeast(0)
            category.text = categories.getOrNull(categoryIndex)?.name ?: "请选择分类"
        }
        showCategories(originalDirection)

        val walletUid = initial.wallet?.accountUid
        val wallets = store.accounts().filter { it.type == AccountType.ASSET && (!it.archived || it.uid == walletUid) }
        var walletIndex = wallets.indexOfFirst { it.uid == walletUid }
        wallet.text = wallets.getOrNull(walletIndex)?.name ?: "请选择钱包"
        val directionNames = listOf("支出", "收入")
        direction.text = directionNames[directions.indexOf(originalDirection)]
        if (initial.bill.refund != null) listOf(amount, merchant, time, direction).forEach { it.isEnabled = false }

        val formScroll = ScrollView(context).apply { addView(form); isFillViewport = true }
        val choiceList = ListView(context).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            visibility = View.GONE
            divider = null
        }
        val content = FrameLayout(context).apply {
            addView(formScroll, FrameLayout.LayoutParams(-1, -2))
            addView(choiceList, FrameLayout.LayoutParams(-1, -2))
        }
        val panel = AlertDialog.Builder(context)
            .setTitle("编辑账单")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存修改", null)
            .create()
        val window = requireNotNull(panel.window)
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        panel.setOnDismissListener { if (dialog === panel) dialog = null }
        try {
            panel.show()
        } catch (_: WindowManager.BadTokenException) {
            Toast.makeText(service, "小窗暂不可用，请在账本中编辑", Toast.LENGTH_SHORT).show()
            return
        }
        dialog = panel
        form.requestFocus()

        val save = panel.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = panel.getButton(AlertDialog.BUTTON_NEGATIVE)
        fun showForm() {
            choiceList.visibility = View.GONE
            formScroll.visibility = View.VISIBLE
            panel.setTitle("编辑账单")
            save.visibility = View.VISIBLE
            cancel.text = "取消"
        }
        fun choose(title: String, labels: List<String>, selected: Int, field: Button, onSelect: (Int) -> Unit) {
            if (labels.isEmpty()) return
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(content.windowToken, 0)
            panel.setTitle(title)
            formScroll.visibility = View.GONE
            choiceList.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, labels)
            choiceList.clearChoices()
            choiceList.visibility = View.VISIBLE
            if (selected in labels.indices) {
                choiceList.setItemChecked(selected, true)
                choiceList.setSelection(selected)
            }
            choiceList.setOnItemClickListener { _, _, position, _ ->
                onSelect(position)
                showForm()
                field.requestFocus()
            }
            save.visibility = View.GONE
            cancel.text = "返回"
        }
        cancel.setOnClickListener {
            if (choiceList.visibility == View.VISIBLE) showForm() else panel.dismiss()
        }
        panel.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && choiceList.visibility == View.VISIBLE) {
                if (event.action == KeyEvent.ACTION_UP) showForm()
                true
            } else false
        }
        category.setOnClickListener {
            choose("选择分类", categories.map { it.name }, categoryIndex, category) { position ->
                categoryIndex = position
                category.text = categories[position].name
            }
        }
        wallet.setOnClickListener {
            choose("选择钱包", wallets.map { it.name }, walletIndex, wallet) { position ->
                walletIndex = position
                wallet.text = wallets[position].name
            }
        }
        direction.setOnClickListener {
            choose("收支方向", directionNames, directions.indexOf(selectedDirection), direction) { position ->
                if (selectedDirection != directions[position]) showCategories(directions[position])
                direction.text = directionNames[position]
            }
        }
        save.setOnClickListener {
            val existing = requireNotNull(record)
            val amountCents = Money.parseCents(amount.text.toString().trim())
            if (amountCents == null || amountCents <= 0L && initial.bill.refund == null) {
                amount.error = "请输入大于零、最多两位小数的金额"
                amount.requestFocus()
                return@setOnClickListener
            }
            val categoryId = categories.getOrNull(categoryIndex)?.id
            if (categoryId == null) { showError("请选择分类"); return@setOnClickListener }
            val account = wallets.getOrNull(walletIndex)
            if (account == null) { showError("请选择钱包"); return@setOnClickListener }
            val editedTime = time.text.toString().trim()
            val occurredAt = if (editedTime == originalTime) initial.bill.occurredAt else runCatching {
                LocalDateTime.parse(editedTime, timeFormat).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()?.takeIf { it > 0L }
            if (occurredAt == null) {
                time.error = "请输入有效时间，格式为年-月-日 时:分"
                time.requestFocus()
                return@setOnClickListener
            }
            val targetDirection = selectedDirection
            val merchantName = merchant.text.toString().trim()
            val noteText = note.text.toString().trim()
            save.isEnabled = false
            cancel.isEnabled = false
            panel.setCancelable(false)
            error.visibility = View.GONE
            app.scope.launch(Dispatchers.Main) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        synchronized(store) {
                            val target = store.category(categoryId, targetDirection)
                            val bill = existing.bill.copy(category = target.name, amountCents = amountCents,
                                direction = targetDirection, merchant = merchantName, occurredAt = occurredAt)
                            store.saveEdited(existing.copy(bill = bill, note = noteText), walletAccountUid = account.uid,
                                manualWalletSelected = account.uid != existing.wallet?.accountUid,
                                submissionToken = UUID.randomUUID().toString(), manualCategorySelected = categoryId != originalCategory)
                        }
                    }
                }
                val saved = result.getOrNull()
                if (saved != null) app.changed(true)
                if (dialog !== panel) return@launch
                save.isEnabled = true
                cancel.isEnabled = true
                panel.setCancelable(true)
                when {
                    saved == null -> showError((result.exceptionOrNull() as? IllegalArgumentException)?.message ?: "保存失败，请重试")
                    saved.state == RecordState.REVIEW -> {
                        record = saved
                        showError(saved.reason.ifBlank { "请核对账单后保存" })
                    }
                    else -> {
                        panel.dismiss()
                        Toast.makeText(service, "修改已保存", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private fun editText(context: Context, value: String, type: Int, limit: Int) = EditText(context).apply {
        inputType = type
        filters = arrayOf(InputFilter.LengthFilter(limit))
        setText(value)
    }

    private fun addField(parent: LinearLayout, name: String, field: View) {
        field.id = View.generateViewId()
        field.minimumHeight = dp(48)
        parent.addView(TextView(parent.context).apply {
            text = name
            labelFor = field.id
            setPadding(0, dp(8), 0, 0)
        })
        parent.addView(field, LinearLayout.LayoutParams(-1, -2))
    }

    companion object {
        private val directions = listOf(Direction.EXPENSE, Direction.INCOME)
        private val timeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    }
}
