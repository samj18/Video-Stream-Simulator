package com.streamsim.web;

/** The small HTML pages served by the app (same colours as the desktop UI). */
final class Pages {

    private Pages() {
    }

    private static final String HEAD = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Video Stream Simulator</title>
            <style>
              :root { --bg:#061228; --panel:#0c1d3d; --accent:#5b3df5; --text:#e8ecf6; --muted:#8fa0c4; }
              * { box-sizing:border-box; }
              body { margin:0; background:var(--bg); color:var(--text); font:15px system-ui,Segoe UI,Roboto,sans-serif; }
              main { max-width:1100px; margin:0 auto; padding:24px; }
              h1 { font-size:20px; margin:0 0 4px; } h2 { font-size:15px; margin:24px 0 8px; color:var(--muted); font-weight:600; }
              p { color:var(--muted); margin:4px 0 12px; }
              video { width:100%; background:#020813; border-radius:10px; max-height:72vh; }
              button { background:var(--accent); color:#fff; border:0; border-radius:8px; padding:9px 18px; font-size:14px; cursor:pointer; }
              button:hover { background:#7358ff; } button:disabled { opacity:.45; cursor:default; }
              code, pre { background:var(--panel); border-radius:6px; padding:2px 6px; color:#cdd6f0; }
              pre { padding:10px 12px; overflow:auto; }
              .row { display:flex; gap:10px; align-items:center; flex-wrap:wrap; margin:10px 0; }
              #status { color:var(--muted); }
            </style></head><body><main>
            <h1>Video Stream Simulator</h1>
            """;

    private static final String TAIL = "</main></body></html>";

    static String index(boolean webrtc, String fileName) {
        StringBuilder sb = new StringBuilder(HEAD);
        if (webrtc) {
            sb.append("""
                    <p>Live stream over WebRTC (WHEP endpoint: <code>/whep</code>).</p>
                    <video id="v" autoplay muted playsinline controls></video>
                    <div class="row"><button id="play">Connect</button><button id="stop" disabled>Disconnect</button><span id="status">idle</span></div>
                    <script>
                    let pc = null, location_ = null;
                    const $ = id => document.getElementById(id);
                    function setStatus(t){ $('status').textContent = t; }
                    async function connect(){
                      $('play').disabled = true; setStatus('connecting...');
                      pc = new RTCPeerConnection();
                      pc.addTransceiver('video', {direction:'recvonly'});
                      pc.ontrack = e => { $('v').srcObject = e.streams[0] || new MediaStream([e.track]); };
                      pc.onconnectionstatechange = () => { setStatus(pc.connectionState);
                        if (['failed','closed'].includes(pc.connectionState)) disconnect(); };
                      await pc.setLocalDescription(await pc.createOffer());
                      await new Promise(res => {                      // WHEP is one round trip: send the complete offer
                        if (pc.iceGatheringState === 'complete') return res();
                        pc.onicegatheringstatechange = () => pc.iceGatheringState === 'complete' && res();
                        setTimeout(res, 2000);
                      });
                      const r = await fetch('/whep', {method:'POST', headers:{'Content-Type':'application/sdp'}, body: pc.localDescription.sdp});
                      if (!r.ok) { setStatus('server error ' + r.status + ' ' + await r.text()); disconnect(); return; }
                      location_ = r.headers.get('Location');
                      await pc.setRemoteDescription({type:'answer', sdp: await r.text()});
                      $('stop').disabled = false;
                    }
                    function disconnect(){
                      if (location_) fetch(location_, {method:'DELETE'}).catch(()=>{});
                      if (pc) pc.close(); pc = null; location_ = null;
                      $('v').srcObject = null; $('play').disabled = false; $('stop').disabled = true;
                    }
                    $('play').onclick = () => connect().catch(e => { setStatus('error: ' + e); disconnect(); });
                    $('stop').onclick = () => { disconnect(); setStatus('idle'); };
                    connect().catch(e => setStatus('error: ' + e));
                    </script>
                    """);
        }
        if (fileName != null) {
            String esc = escape(fileName);
            sb.append("<h2>HTTP file with Range requests</h2>")
                    .append("<p>Seek freely: the browser / player requests just the byte ranges it needs.</p>")
                    .append("<video controls preload=\"metadata\" src=\"/video\"></video>")
                    .append("<p>Direct link for VLC, ffplay, curl: <code>/video</code> or <code>/").append(esc).append("</code></p>")
                    .append("<pre>curl -r 0-99 -i http://&lt;this-pc&gt;/video</pre>");
        }
        return sb.append(TAIL).toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
