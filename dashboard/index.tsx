import React, { useState, useEffect, useRef } from 'react';
import { 
  Activity, Cpu, Wifi, Music, Smartphone, Server, Terminal, Clock, 
  Play, Pause, RefreshCw, BarChart3, ShieldAlert
} from 'lucide-react';

// --- Types ---

interface SyncNode {
  id: string;
  type: 'host' | 'client';
  latency: number; 
  offset: number;  
  status: 'idle' | 'syncing' | 'ready' | 'playing';
}

interface LogEntry {
  timestamp: string;
  source: string;
  message: string;
  type: 'info' | 'warn' | 'error' | 'success';
}

const App = () => {
  const [nodes, setNodes] = useState<SyncNode[]>([]);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [isPlaying, setIsPlaying] = useState(false);
  const [socket, setSocket] = useState<WebSocket | null>(null);

  const addLog = (message: string, source: string = 'SYSTEM', type: 'info' | 'warn' | 'error' | 'success' = 'info') => {
    setLogs(prev => [{
      timestamp: new Date().toLocaleTimeString(),
      source,
      message,
      type
    }, ...prev].slice(0, 50));
  };

  useEffect(() => {
    // Connect to Real Rust Server
    const ws = new WebSocket('ws://' + window.location.hostname + ':3000/ws?mode=dashboard');
    
    ws.onopen = () => {
      addLog('Connected to SONICSYNC Rust Core', 'NETWORK', 'success');
      ws.send(JSON.stringify({ Join: { device_id: 'DASHBOARD' } }));
      setSocket(ws);
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        
        if (msg.Welcome) {
            addLog(`Session Established: ${msg.Welcome.session_id}`, 'AUTH');
        }
        
        if (msg.PlayCommand) {
            addLog(`PLAY BROADCAST: ${msg.PlayCommand.track_url} @ ${msg.PlayCommand.start_at_server_time}`, 'CORE', 'info');
            setIsPlaying(true);
        }
        
        // Note: Real Telemetry updates would require the server to broadcast 'PeerUpdate' messages.
        // For Phase 3, we act as a "Host" that can trigger play.
        
      } catch (e) {
        console.error("Parse error", e);
      }
    };

    ws.onclose = () => {
      addLog('Disconnected from Server', 'NETWORK', 'error');
      setSocket(null);
    };

    return () => ws.close();
  }, []);

  const handlePlay = () => {
    if (!socket) return;
    
    addLog('Requesting Cluster Playback...', 'CMD');
    // Send PlayRequest to Rust Server
    const req = {
        PlayRequest: {
            track_url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
            delay_ms: 3000
        }
    };
    socket.send(JSON.stringify(req));
  };

  return (
    <div className="min-h-screen flex flex-col bg-[#050507] text-slate-100 font-sans">
      {/* NOC Header */}
      <header className="h-14 border-b border-white/5 bg-black/80 backdrop-blur-3xl flex items-center justify-between px-6 sticky top-0 z-[100]">
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 via-cyan-500 to-emerald-500 rounded flex items-center justify-center">
              <Terminal size={16} className="text-white" />
            </div>
            <div>
              <h1 className="font-black text-xs tracking-widest uppercase leading-none">SonicSync <span className="text-cyan-400">LIVE</span></h1>
              <p className="text-[9px] text-slate-500 font-mono mt-0.5">RUST_BACKEND_CONNECTED: {socket ? 'YES' : 'NO'}</p>
            </div>
          </div>
        </div>
      </header>

      <main className="flex-1 flex overflow-hidden">
        <aside className="w-[340px] border-r border-white/5 bg-[#0a0a0c] p-6 flex flex-col gap-8">
             <button 
               onClick={handlePlay}
               disabled={!socket}
               className={`w-full py-5 rounded-2xl flex flex-col items-center justify-center gap-1 font-black transition-all ${
                 socket ? 'bg-emerald-500 text-black hover:scale-105' : 'bg-slate-800 text-slate-500'
               }`}
             >
               <div className="flex items-center gap-3">
                 <Play size={20} fill="currentColor" />
                 <span>BROADCAST PLAY</span>
               </div>
             </button>
             
             <div className="flex-1 overflow-y-auto font-mono text-[10px] space-y-2">
                {logs.map((log, i) => (
                  <div key={i} className="flex gap-2">
                     <span className="text-slate-600">[{log.timestamp}]</span>
                     <span className="text-cyan-400 font-bold">{log.source}</span>
                     <span className="text-slate-400">{log.message}</span>
                  </div>
                ))}
             </div>
        </aside>

        <section className="flex-1 flex flex-col bg-black items-center justify-center text-slate-600">
             <Server size={64} className="mb-4 opacity-20" />
             <p className="font-mono text-xs">Awaiting Global Telemetry Stream...</p>
             <p className="text-[10px] mt-2 max-w-md text-center">To see live devices, the Server needs to implement a `PeerList` broadcast. This dashboard currently supports initiating playback and logging server events.</p>
        </section>
      </main>
    </div>
  );
};

export default App;
