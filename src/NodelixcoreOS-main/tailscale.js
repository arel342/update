'use strict';
// ============================================================================
// tailscale.js — Integrasi Tailscale (opsional) untuk NodelixcoreOS.
//
// PENTING: modul ini TIDAK PERNAH membuat server hang.
// Semua execFile punya timeout keras (5 detik status, 3 detik ip);
// kegagalan = nilai null / status unknown, tidak pernah men-blok.
//
// Jalur startup (banner) HANYA memakai tailscaleOrigin() — sinkron,
// murni dari .env, tanpa menjalankan binary apa pun.
//
// Jalur on-demand (GET /api/tailscale/status) membolehkan
// tailscaleStatus() / tailscaleIp() dengan timeout 5/3 detik.
//
// .env vars:
//   TAILSCALE_ENABLE=0|1
//   TAILSCALE_IP=100.x.y.z
//   TAILSCALE_PORT=3000
//   TAILSCALE_ORIGIN=http://100.x.y.z:3000
//   TAILSCALE_TAILNET=xxxx.ts.net
// ============================================================================

const { execFile } = require('child_process');
const path = require('path');
const fs = require('fs');

function findTailscaleBin() {
    const dirs = String(process.env.PATH || '').split(path.delimiter).filter(Boolean);
    for (const d of dirs) {
        const p = path.join(d, 'tailscale');
        try { if (fs.existsSync(p)) return p; } catch { /* ignore */ }
    }
    return 'tailscale';
}

// `tailscale status --json` — timeout 5 detik. TIDAK PERNAH reject.
function tailscaleStatus() {
    return new Promise(resolve => {
        const bin = findTailscaleBin();
        let settled = false;
        const done = v => { if (!settled) { settled = true; resolve(v); } };
        const child = execFile(bin, ['status', '--json'],
            { timeout: 5000, maxBuffer: 256 * 1024, killSignal: 'SIGKILL' },
            (err, stdout) => {
                if (err) return done({ ok: false, error: err.message || 'tailscale unavailable' });
                try {
                    const data = JSON.parse(stdout);
                    const self = data.Self || {};
                    done({
                        ok: true,
                        online: self.Online !== undefined ? self.Online : null,
                        backendState: data.BackendState || null,
                        tailscaleIp: (self.TailscaleIPs && self.TailscaleIPs[0]) || self.DNSName || '',
                        dnsName: self.DNSName || null
                    });
                } catch (e) { done({ ok: false, error: 'parse: ' + e.message }); }
            });
        child.on('error', e => done({ ok: false, error: e.message || 'spawn error' }));
    });
}

// `tailscale ip -4` — timeout 3 detik. Resolve null jika gagal.
function tailscaleIp() {
    return new Promise(resolve => {
        const bin = findTailscaleBin();
        let settled = false;
        const done = v => { if (!settled) { settled = true; resolve(v); } };
        const child = execFile(bin, ['ip', '-4'],
            { timeout: 3000, maxBuffer: 8192, killSignal: 'SIGKILL' },
            (err, stdout) => {
                if (err) return done(null);
                done(String(stdout || '').trim() || null);
            });
        child.on('error', () => done(null));
    });
}

// Sinkron — murni dari env vars, TIDAK menjalankan binary apa pun.
// Dipakai di jalur startup (banner) supaya tidak ada yang bisa hang.
function tailscaleOrigin(envMap) {
    const env = envMap || process.env;
    const explicit = String(env.TAILSCALE_ORIGIN || '').trim();
    if (explicit) return explicit;
    const ip = String(env.TAILSCALE_IP || '').trim();
    if (ip) return `http://${ip}:${env.TAILSCALE_PORT || env.PORT || '3000'}`;
    return null;
}

module.exports = { findTailscaleBin, tailscaleStatus, tailscaleIp, tailscaleOrigin };

// Jika dijalankan langsung: node tailscale.js
if (require.main === module) {
    (async () => {
        process.stdout.write(JSON.stringify(await tailscaleStatus(), null, 2) + '\n');
    })();
}
