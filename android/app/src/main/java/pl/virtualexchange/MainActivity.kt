
package pl.virtualexchange

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.virtualexchange.data.Api

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=darkColorScheme()){ ExchangeApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeApp(){
    val scope= rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var assets by remember { mutableStateOf(listOf<JSONObject>()) }
    var portfolio by remember { mutableStateOf(listOf<JSONObject>()) }
    var selected by remember { mutableStateOf("BTC") }
    var bids by remember { mutableStateOf("Brak") }
    var asks by remember { mutableStateOf("Brak") }
    var status by remember { mutableStateOf("Łączenie…") }
    var side by remember { mutableStateOf("buy") }
    var kind by remember { mutableStateOf("limit") }
    var qty by remember { mutableStateOf("0.01") }
    var price by remember { mutableStateOf("65000") }
    var message by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var ticker by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var supply by remember { mutableStateOf("1000000") }

    fun refresh(){
        scope.launch {
            try{
                val a=Api.getArray("assets")
                assets=(0 until a.length()).map{a.getJSONObject(it)}
                val p=Api.getArray("portfolio",true)
                portfolio=(0 until p.length()).map{p.getJSONObject(it)}
                val book=Api.getObject("orderbook/$selected")
                bids=levels(book.optJSONArray("bids"))
                asks=levels(book.optJSONArray("asks"))
                status="ONLINE"
            }catch(e:Exception){status="OFFLINE: ${e.message}"}
        }
    }

    LaunchedEffect(Unit){refresh()}
    LaunchedEffect(selected){
        try{
            val b=Api.getObject("orderbook/$selected")
            bids=levels(b.optJSONArray("bids"))
            asks=levels(b.optJSONArray("asks"))
        }catch(_:Exception){}
    }

    Scaffold(
        topBar={TopAppBar(title={Column{
            Text("VIRTUAL EXCHANGE")
            Text(status,style=MaterialTheme.typography.labelSmall)
        }},actions={TextButton(onClick={refresh()}){Text("ODŚWIEŻ")}})},
        bottomBar={
            NavigationBar{
                NavigationBarItem(tab==0,{tab=0},{Text("⌂")},label={Text("Rynek")})
                NavigationBarItem(tab==1,{tab=1},{Text("▤")},label={Text("Portfel")})
                NavigationBarItem(tab==2,{tab=2},{Text("+")},label={Text("Utwórz")})
            }
        }
    ){ padding->
        when(tab){
            0->LazyColumn(Modifier.padding(padding).padding(14.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)){
                item{Text("RYNEK",style=MaterialTheme.typography.headlineMedium)}
                items(assets){asset->
                    ElevatedCard(onClick={
                        selected=asset.optString("symbol")
                        price=asset.optString("last_price","1").let{if(it=="0")"1" else it}
                    }){
                        Row(Modifier.fillMaxWidth().padding(15.dp),
                            horizontalArrangement=Arrangement.SpaceBetween){
                            Column{
                                Text(asset.optString("symbol"),style=MaterialTheme.typography.titleMedium)
                                Text(asset.optString("name"))
                            }
                            Text("$"+asset.optString("last_price"))
                        }
                    }
                }
                item{
                    Text("ARKUSZ ZLECEŃ: $selected",style=MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth()){
                        Column(Modifier.weight(1f)){Text("KUPNO");Text(bids)}
                        Column(Modifier.weight(1f)){Text("SPRZEDAŻ");Text(asks)}
                    }
                    HorizontalDivider(Modifier.padding(vertical=10.dp))
                    Text("ZŁÓŻ ZLECENIE",style=MaterialTheme.typography.titleLarge)
                    Row{
                        FilterChip(side=="buy",{side="buy"},label={Text("KUP")})
                        Spacer(Modifier.width(8.dp))
                        FilterChip(side=="sell",{side="sell"},label={Text("SPRZEDAJ")})
                    }
                    Row{
                        FilterChip(kind=="limit",{kind="limit"},label={Text("LIMIT")})
                        Spacer(Modifier.width(8.dp))
                        FilterChip(kind=="market",{kind="market"},label={Text("MARKET")})
                    }
                    OutlinedTextField(qty,{qty=it},label={Text("Ilość")},modifier=Modifier.fillMaxWidth())
                    if(kind=="limit") OutlinedTextField(price,{price=it},
                        label={Text("Cena USD")},modifier=Modifier.fillMaxWidth())
                    Button(onClick={
                        scope.launch{
                            try{
                                val body=JSONObject().put("symbol",selected).put("side",side)
                                    .put("order_type",kind).put("quantity",qty)
                                if(kind=="limit")body.put("price",price)
                                val result=Api.post("orders",body)
                                message="Zlecenie #${result.optInt("order_id")}: ${result.optString("status")}"
                                refresh()
                            }catch(e:Exception){message=e.message ?: "Błąd"}
                        }
                    },modifier=Modifier.fillMaxWidth()){Text("WYŚLIJ ZLECENIE")}
                    Text(message)
                }
            }
            1->LazyColumn(Modifier.padding(padding).padding(16.dp)){
                item{Text("PORTFEL",style=MaterialTheme.typography.headlineMedium)}
                items(portfolio){row->
                    ElevatedCard(Modifier.fillMaxWidth().padding(vertical=4.dp)){
                        Column(Modifier.padding(14.dp)){
                            Text(row.optString("symbol"),style=MaterialTheme.typography.titleMedium)
                            Text("Dostępne: ${row.optString("available")}")
                            Text("Zablokowane: ${row.optString("locked")}")
                        }
                    }
                }
            }
            else->Column(Modifier.padding(padding).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)){
                Text("STWÓRZ WŁASNE AKTYWO",style=MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name,{name=it},label={Text("Nazwa")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(ticker,{ticker=it.uppercase()},label={Text("Ticker")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(description,{description=it},label={Text("Opis")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(supply,{supply=it},label={Text("Podaż")},modifier=Modifier.fillMaxWidth())
                Button(onClick={
                    scope.launch{
                        try{
                            val body=JSONObject().put("name",name).put("symbol",ticker)
                                .put("description",description).put("total_supply",supply)
                            val result=Api.post("assets",body)
                            message="Utworzono ${result.optString("symbol")}"
                            name="";ticker="";description=""
                            refresh()
                        }catch(e:Exception){message=e.message ?: "Błąd"}
                    }
                },modifier=Modifier.fillMaxWidth()){Text("UTWÓRZ AKTYWO")}
                Text(message)
            }
        }
    }
}

private fun levels(array:org.json.JSONArray?):String{
    if(array==null||array.length()==0)return "Brak zleceń"
    return (0 until minOf(array.length(),6)).joinToString("\n"){
        val r=array.getJSONObject(it)
        "${r.optString("price")} × ${r.optString("quantity")}"
    }
}
