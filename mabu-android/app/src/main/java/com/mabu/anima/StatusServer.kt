package com.mabu.anima

import android.content.Context
import android.util.Log
import com.mabu.anima.DeviceStats.toJson
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * LAN-only HTTP server: live telemetry + a browser dashboard to monitor and
 * configure the robot, so the brain PC (or any browser on the LAN) doesn't need
 * an ADB shell or the on-device settings panel.
 *
 *   GET  /            -> the dashboard (HTML; live charts + config controls)
 *   GET  /status      -> full DeviceStats snapshot (JSON; the poll source)
 *   GET  /config      -> current tunable values (JSON)
 *   POST /config      -> apply+persist tunables (x-www-form-urlencoded body)
 *   POST /mode        -> set animation mode (form: mode=PUPPET|FOLLOW|IDLE|SLEEP)
 *   GET  /healthz     -> "ok"
 *
 * No auth, no TLS -- LAN trust model, same as the rest of the brain<->device
 * traffic. Bound to 0.0.0.0:7862 (clear of the brain's 7860/7861/9090/8123).
 */
class StatusServer(
    private val context: Context,
    private val hooks: Hooks? = null,
    private val port: Int = 7862,
) {
    /** Control surface implemented by MainActivity. configJson is read off the
     *  HTTP thread (plain-field reads, race-tolerant); applyConfig/setMode must
     *  marshal to the main thread (they touch TuningSettings/motors/UI). */
    interface Hooks {
        fun configJson(): JSONObject
        fun applyConfig(params: Map<String, String>)
        fun setMode(mode: String)
        /** Restore behavioral tuning defaults (preserves hardware calibration). */
        fun resetConfig()
        /** Show a full-screen calibration prompt on the device. Empty title hides it. */
        fun showPrompt(title: String, phase: String, upcoming: String)
    }

    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(3)
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        try {
            serverSocket = ServerSocket(port).apply { reuseAddress = true }
            running = true
            acceptThread = Thread({ acceptLoop() }, "StatusServer-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "listening on :$port")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to bind :$port", t)
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: SocketException) {
                return
            } catch (t: Throwable) {
                Log.w(TAG, "accept error", t)
                continue
            }
            workers.execute { handle(client) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val rawPath = parts.getOrNull(1) ?: ""
                val path = rawPath.substringBefore('?')
                val out = sock.getOutputStream()

                // Read the body for POSTs (form-urlencoded).
                val body = if (method == "POST" && contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                when {
                    method == "GET" && (path == "/" || path == "/ui") ->
                        write(out, 200, "text/html", DASHBOARD_HTML)
                    method == "GET" && (path == "/status" || path == "/status.json") -> {
                        val json = with(DeviceStats) { snapshot(context).toJson() }.toString()
                        write(out, 200, "application/json", json)
                    }
                    method == "GET" && path == "/config" -> {
                        val json = (hooks?.configJson() ?: JSONObject()).toString()
                        write(out, 200, "application/json", json)
                    }
                    method == "GET" && path == "/healthz" ->
                        write(out, 200, "text/plain", "ok")
                    method == "POST" && path == "/config" -> {
                        hooks?.applyConfig(parseForm(body))
                        write(out, 200, "application/json", "{\"ok\":true}")
                    }
                    method == "POST" && path == "/mode" -> {
                        val mode = parseForm(body)["mode"]
                        if (mode != null) hooks?.setMode(mode)
                        write(out, 200, "application/json", "{\"ok\":true}")
                    }
                    method == "POST" && path == "/reset" -> {
                        hooks?.resetConfig()
                        write(out, 200, "application/json", "{\"ok\":true}")
                    }
                    method == "POST" && path == "/prompt" -> {
                        val f = parseForm(body)
                        hooks?.showPrompt(f["title"] ?: "", f["phase"] ?: "", f["upcoming"] ?: "")
                        write(out, 200, "application/json", "{\"ok\":true}")
                    }
                    else -> write(out, 404, "text/plain", "not found")
                }
            }
        } catch (e: IOException) {
            // client hung up mid-response; not worth logging
        } catch (t: Throwable) {
            Log.w(TAG, "handler error", t)
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in body.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val k = dec(pair.substring(0, i))
            val v = dec(pair.substring(i + 1))
            map[k] = v
        }
        return map
    }

    private fun dec(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun write(out: OutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Status"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "StatusServer"

        // Self-contained dashboard: no external CDNs (works on an offline LAN).
        // Vanilla JS + a hand-rolled canvas line chart. Polls /status at 10 Hz
        // for charts/readouts; loads /config once to build the tuning panel and
        // POSTs changes (debounced). JS avoids ${ } so it survives Kotlin's raw
        // string interpolation.
        private val DASHBOARD_HTML = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mabu monitor</title>
<style>
 body{margin:0;background:#0f1115;color:#cdd3de;font:13px/1.4 system-ui,sans-serif}
 header{padding:8px 14px;background:#171a21;border-bottom:1px solid #262b36;display:flex;gap:14px;align-items:center;flex-wrap:wrap}
 header b{color:#fff}
 .pill{padding:2px 8px;border-radius:10px;background:#222836}
 .modes button{margin-right:6px;background:#2a3140;color:#cdd3de;border:1px solid #3a4252;border-radius:6px;padding:4px 10px;cursor:pointer}
 .modes button.on{background:#3a6df0;border-color:#3a6df0;color:#fff}
 main{display:grid;grid-template-columns:1fr 340px;gap:14px;padding:14px}
 @media(max-width:820px){main{grid-template-columns:1fr}}
 .card{background:#171a21;border:1px solid #262b36;border-radius:8px;padding:10px}
 .card h3{margin:0 0 8px;font-size:12px;color:#9aa3b2;text-transform:uppercase;letter-spacing:.05em}
 canvas{width:100%;height:200px;display:block}
 table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}
 td,th{text-align:right;padding:2px 6px}th{color:#9aa3b2;font-weight:600}
 td.k,th.k{text-align:left}
 .row{display:flex;align-items:center;gap:8px;margin:7px 0}
 .row label{flex:0 0 150px;color:#aeb6c4}
 .row input[type=range]{flex:1}
 .row .v{flex:0 0 52px;text-align:right;color:#fff;font-variant-numeric:tabular-nums}
 .jit{font-variant-numeric:tabular-nums}
 .bar{display:inline-block;height:9px;background:#3a6df0;border-radius:2px;vertical-align:middle}
 small{color:#6b7384}
 select,input[type=checkbox]{accent-color:#3a6df0}
</style></head><body>
<header>
 <b>Mabu</b>
 <span class="pill">mode <b id="mode">-</b></span>
 <span class="pill">blink <b id="blink">-</b></span>
 <span class="pill">cmd <b id="cmdhz">-</b> Hz</span>
 <span class="pill">mlkit <b id="fps">-</b> fps</span>
 <span class="pill" id="conn">connecting...</span>
 <span class="modes" id="modes"></span>
</header>
<main>
 <div>
  <div class="card"><h3>Motor positions (0-100)</h3><canvas id="pos"></canvas>
   <div id="legend" style="margin-top:6px"></div></div>
  <div class="card" style="margin-top:14px"><h3>Jitter (stddev, last ~2s)</h3>
   <table id="jtab"><thead><tr><th class="k">motor</th><th>pos</th><th>target</th><th>jitter</th><th>peak</th><th></th></tr></thead><tbody></tbody></table>
  </div>
  <div class="card" style="margin-top:14px"><h3>Animation inputs</h3>
   <table id="anim"><tbody></tbody></table></div>
 </div>
 <div class="card"><h3>Tuning</h3><div id="cfg"></div>
  <small>Changes apply live and persist on the device.</small></div>
</main>
<script>
var SERIES=[["eyes_lr","#3a6df0"],["eyes_ud","#41c7c7"],["neck_rot","#e0843a"],
 ["neck_elev","#e0c63a"],["neck_tilt","#b07ce8"],["eyelid_l","#4fd06a"],["eyelid_r","#d04f8a"]];
var TARGET={eyes_lr:"target_eyes_lr",eyes_ud:"target_eyes_ud",neck_rot:"target_neck_rot",
 neck_elev:"target_neck_elev",neck_tilt:"target_neck_tilt"};
var N=150, buf={}, peak={};
SERIES.forEach(function(s){buf[s[0]]=[];peak[s[0]]=0;});

// Config schema (label,min,max,step). Bools and the select handled separately.
var CFG=[
 ["eyeGazeGain","Eye gaze gain",0,3,0.05],
 ["eyeGazeInputAlpha","Eye input smooth",0.05,1,0.01],
 ["eyeGazeDeadband","Eye deadband",0,0.25,0.005],
 ["smoothAlphaEyes","Eye tween a",0.05,1,0.01],
 ["smoothAlphaNeck","Neck tween a",0.02,1,0.01],
 ["neckAngleRange","Neck angle range",10,60,1],
 ["eyelidCoupling","Eyelid coupling",0,1,0.02],
 ["eyelidWinkOpen","Wink-open thresh",0.4,1,0.01],
 ["eyelidCloseLevel","Blink-close thresh",0.05,0.6,0.01],
 ["eyelidOpenInputAlpha","Eyelid smooth",0.05,1,0.01],
 ["eyelidBlinkHoldMs","Blink hold ms",0,400,10],
 ["eyelidPoseSoftDeg","Pose soft (deg)",0,45,1],
 ["eyelidPoseLimitDeg","Pose limit (deg)",10,90,1],
 ["gazeYOffset","Gaze Y offset",-0.3,0.3,0.01]
];
var BOOLS=[["useEyeGaze","Use eye gaze"]];

function post(url,obj){var b=Object.keys(obj).map(function(k){
 return encodeURIComponent(k)+"="+encodeURIComponent(obj[k]);}).join("&");
 return fetch(url,{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:b});}

var dbT=null,dbQ={};
function setCfg(k,v){dbQ[k]=v;if(dbT)clearTimeout(dbT);
 dbT=setTimeout(function(){var q=dbQ;dbQ={};post("/config",q);},150);}

function buildCfg(cur){
 var c=document.getElementById("cfg");c.innerHTML="";
 CFG.forEach(function(f){
  var k=f[0];var v=cur[k];if(v===undefined)return;
  var row=document.createElement("div");row.className="row";
  var lab=document.createElement("label");lab.textContent=f[1];
  var inp=document.createElement("input");inp.type="range";
  inp.min=f[2];inp.max=f[3];inp.step=f[4];inp.value=v;
  var out=document.createElement("span");out.className="v";out.textContent=(+v).toFixed(2);
  inp.oninput=function(){out.textContent=(+inp.value).toFixed(2);setCfg(k,inp.value);};
  row.appendChild(lab);row.appendChild(inp);row.appendChild(out);c.appendChild(row);
 });
 BOOLS.forEach(function(f){
  var k=f[0];var row=document.createElement("div");row.className="row";
  var lab=document.createElement("label");lab.textContent=f[1];
  var inp=document.createElement("input");inp.type="checkbox";inp.checked=!!cur[k];
  inp.onchange=function(){setCfg(k,inp.checked?"true":"false");};
  row.appendChild(lab);row.appendChild(inp);c.appendChild(row);});
 // blink method select
 var row=document.createElement("div");row.className="row";
 var lab=document.createElement("label");lab.textContent="Blink method";
 var sel=document.createElement("select");
 ["spontaneous","mirror","both","none"].forEach(function(o){
  var op=document.createElement("option");op.value=o;op.textContent=o;
  if(cur.blinkMethod===o)op.selected=true;sel.appendChild(op);});
 sel.onchange=function(){setCfg("blinkMethod",sel.value);};
 row.appendChild(lab);row.appendChild(sel);c.appendChild(row);
}

var MODES=["FOLLOW","PUPPET","IDLE","SLEEP"];
function buildModes(){var m=document.getElementById("modes");
 MODES.forEach(function(x){var b=document.createElement("button");b.textContent=x;
  b.onclick=function(){post("/mode",{mode:x});};b.id="m_"+x;m.appendChild(b);});
 var rb=document.createElement("button");rb.textContent="⟲ defaults";rb.title="reset tuning to defaults";
 rb.onclick=function(){if(confirm("Reset tuning to defaults?"))fetch("/reset",{method:"POST"}).then(function(){
  setTimeout(function(){fetch("/config").then(function(r){return r.json();}).then(buildCfg);},300);});};
 m.appendChild(rb);}
function buildLegend(){var l=document.getElementById("legend");l.innerHTML="";
 SERIES.forEach(function(s){var sp=document.createElement("span");
  sp.style.color=s[1];sp.style.marginRight="12px";sp.textContent="● "+s[0];l.appendChild(sp);});}

function stddev(a){if(a.length<2)return 0;var m=0,i;for(i=0;i<a.length;i++)m+=a[i];m/=a.length;
 var s=0;for(i=0;i<a.length;i++){var d=a[i]-m;s+=d*d;}return Math.sqrt(s/a.length);}

function drawChart(){
 var cv=document.getElementById("pos");var dpr=window.devicePixelRatio||1;
 var w=cv.clientWidth,h=cv.clientHeight;cv.width=w*dpr;cv.height=h*dpr;
 var x=cv.getContext("2d");x.scale(dpr,dpr);x.clearRect(0,0,w,h);
 x.strokeStyle="#262b36";x.lineWidth=1;
 for(var g=0;g<=4;g++){var yy=h*g/4;x.beginPath();x.moveTo(0,yy);x.lineTo(w,yy);x.stroke();}
 SERIES.forEach(function(s){var d=buf[s[0]];if(d.length<2)return;
  x.strokeStyle=s[1];x.lineWidth=1.5;x.beginPath();
  for(var i=0;i<d.length;i++){var px=w*i/(N-1);var py=h-(d[i]/100)*h;
   if(i===0)x.moveTo(px,py);else x.lineTo(px,py);}x.stroke();});
}

function setT(id,v){var e=document.getElementById(id);if(e)e.textContent=v;}
function num(v,p){return (v===null||v===undefined)?"--":(+v).toFixed(p===undefined?1:p);}

function tick(){
 fetch("/status").then(function(r){return r.json();}).then(function(d){
  setT("conn","live");document.getElementById("conn").style.color="#4fd06a";
  setT("mode",d.mode);setT("blink",(d.animation||{}).blink_method);
  setT("cmdhz",num((d.motors||{}).cmd_hz));setT("fps",num(d.mlkit_fps));
  MODES.forEach(function(x){var b=document.getElementById("m_"+x);
   if(b)b.className=(d.mode===x)?"on":"";});
  var mot=d.motors||{},ani=d.animation||{};
  SERIES.forEach(function(s){var v=mot[s[0]];if(typeof v==="number"){
   var a=buf[s[0]];a.push(v);if(a.length>N)a.shift();}});
  drawChart();
  // jitter table
  var tb=document.querySelector("#jtab tbody");tb.innerHTML="";
  SERIES.forEach(function(s){var k=s[0];var a=buf[k];var win=a.slice(-20);
   var j=stddev(win);if(j>peak[k])peak[k]=j;
   var tr=document.createElement("tr");
   tr.innerHTML="<td class='k' style='color:"+s[1]+"'>"+k+"</td><td>"+num(mot[k])+
    "</td><td>"+num(ani[TARGET[k]])+"</td><td class='jit'>"+j.toFixed(2)+
    "</td><td>"+peak[k].toFixed(2)+"</td><td><span class='bar' style='width:"+
    Math.min(60,j*8)+"px'></span></td>";tb.appendChild(tr);});
  // anim inputs
  var at=document.querySelector("#anim tbody");
  at.innerHTML=
   "<tr><td class='k'>head yaw/pitch/roll</td><td>"+num(ani.head_yaw)+" / "+num(ani.head_pitch)+" / "+num(ani.head_roll)+"</td></tr>"+
   "<tr><td class='k'>pupil raw</td><td>"+num(ani.pupil_raw_x,2)+", "+num(ani.pupil_raw_y,2)+"</td></tr>"+
   "<tr><td class='k'>pupil filtered</td><td>"+num(ani.pupil_filt_x,2)+", "+num(ani.pupil_filt_y,2)+"</td></tr>"+
   "<tr><td class='k'>eye open L/R</td><td>"+num(ani.eye_open_prob_l,2)+" / "+num(ani.eye_open_prob_r,2)+"</td></tr>"+
   "<tr><td class='k'>eye closed L/R</td><td>"+ani.eye_closed_l+" / "+ani.eye_closed_r+"</td></tr>"+
   "<tr><td class='k'>pose reliability</td><td>"+num(ani.pose_reliability,2)+"</td></tr>"+
   "<tr><td class='k'>transport</td><td>"+d.transport_state+"</td></tr>"+
   "<tr><td class='k'>battery</td><td>"+num(d.battery_pct,0)+"%</td></tr>";
 }).catch(function(){setT("conn","no data");document.getElementById("conn").style.color="#e0843a";});
}

buildModes();buildLegend();
fetch("/config").then(function(r){return r.json();}).then(buildCfg).catch(function(){});
setInterval(tick,100);tick();
</script></body></html>
"""
    }
}
