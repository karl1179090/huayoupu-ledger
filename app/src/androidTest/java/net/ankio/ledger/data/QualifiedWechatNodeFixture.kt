package net.ankio.ledger.data

import net.ankio.ledger.core.*

/** Reduced hierarchy, with entirely synthetic business values. Real qualification is separate. */
internal object QualifiedWechatNodeFixture {
    private val environment=NodeEnvironment("8.0.79",3182,
        "16/api36/samsung/e1qzcx/e1q:16/BP4A.251205.006/S9210ZCS6DZI1:user/release-keys","samsung/SM-S9210")
    private class Tree {
        val nodes=mutableListOf<CaptureNode>()
        fun add(parent:Int?,kind:String,text:String?=null,top:Int=0,bottom:Int=900,left:Int=0,right:Int=400,description:String?=null):Int {
            val id=nodes.size;nodes+=CaptureNode(id,parent,parent?.let { nodes[it].depth+1 } ?: 0,
                NodeBounds(left,top,right,bottom),text,description,kind);return id
        }
    }
    fun page(amount:String="12.34",merchant:String?="合成餐馆",provider:String?="合成扣费服务商",content:String?="合成停车费",second:Boolean=false):NodeSnapshot {
        val t=Tree();val root=t.add(null,"android.widget.FrameLayout")
        val list=t.add(root,"androidx.recyclerview.widget.RecyclerView")
        val service=t.add(list,"android.widget.RelativeLayout",top=0,bottom=250)
        t.add(service,"android.widget.TextView","「先享后付」服务使用通知",top=10,bottom=40)
        t.add(service,"android.widget.TextView","付款方式：优先银行卡自动付款",top=50,bottom=80)
        t.add(service,"android.widget.TextView","订单已关闭；服务商家：合成无关服务商；营销999.00元",top=90,bottom=120)
        fun debit(offset:Int,value:String,name:String?) {
            val item=t.add(list,"android.widget.RelativeLayout",top=300+offset,bottom=850+offset)
            val frame=t.add(item,"android.widget.FrameLayout",top=300+offset,bottom=850+offset)
            val card=t.add(frame,"android.widget.LinearLayout",top=300+offset,bottom=850+offset)
            val header=t.add(card,"android.widget.FrameLayout",top=300+offset,bottom=360+offset)
            val row=t.add(header,"android.widget.LinearLayout",top=300+offset,bottom=360+offset)
            t.add(row,"android.widget.ImageView",top=310+offset,bottom=350+offset,left=10,right=50)
            val title=t.add(row,"android.widget.LinearLayout",top=310+offset,bottom=350+offset,left=60,right=300)
            t.add(title,"android.widget.TextView",name,top=310+offset,bottom=350+offset,left=60,right=300)
            val body=t.add(card,"android.widget.LinearLayout",top=360+offset,bottom=750+offset)
            val panel=t.add(body,"android.widget.LinearLayout",top=360+offset,bottom=550+offset,
                description="通过零钱扣款￥${value}账单详情")
            t.add(panel,"android.widget.TextView","通过零钱扣款",top=370+offset,bottom=400+offset)
            val money=t.add(panel,"android.widget.LinearLayout",top=410+offset,bottom=470+offset)
            t.add(money,"android.widget.TextView","￥",top=410+offset,bottom=470+offset,left=100,right=130)
            t.add(money,"android.widget.TextView",value,top=410+offset,bottom=470+offset,left=130,right=250)
            val status=t.add(panel,"android.widget.LinearLayout",top=480+offset,bottom=530+offset)
            t.add(status,"android.widget.TextView","按时支付，记入微信支付分记录",top=480+offset,bottom=510+offset)
            t.add(status,"android.widget.TextView","合成营销积分8888",top=510+offset,bottom=530+offset)
            val details=t.add(body,"android.widget.LinearLayout",top=550+offset,bottom=700+offset)
            fun field(label:String,value:String?,at:Int) {
                val field=t.add(details,"android.widget.LinearLayout",top=at+offset,bottom=at+30+offset)
                t.add(field,"android.widget.TextView",label,top=at+offset,bottom=at+30+offset,left=0,right=100)
                t.add(field,"android.widget.TextView",value,top=at+offset,bottom=at+30+offset,left=100,right=300)
            }
            field("扣费服务",provider,560);field("扣费内容",content,610)
            val footer=t.add(card,"android.widget.FrameLayout",top=750+offset,bottom=850+offset)
            t.add(footer,"android.widget.TextView","查看订单详情",top=770+offset,bottom=810+offset)
        }
        debit(0,amount,merchant);if(second)debit(600,"56.78","合成第二商家")
        return NodeSnapshot(PaymentApps.WECHAT,7,"com.tencent.mm.ui.chatting.ChattingUI",1,100,110,1_799_400_000_000,environment,t.nodes)
    }
}
